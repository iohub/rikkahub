#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ComfyUI 图像服务：按需独占显存的编排式 HTTP API + 简约 WebUI。

启动（真实环境，会按需停 LLM/TTS 再拉 ComfyUI）::

    python3 server.py --host 0.0.0.0 --port 8080 --base-url http://127.0.0.1:8188

联调（绝不碰真实进程/显存）::

    python3 server.py --port 18080 --service-dry-run --auto-start

接口一览（详见 README 或页面底部）::

* ``GET  /``                          —— 显存编排台 WebUI
* ``GET  /api/health``                —— 服务 + ComfyUI 状态 + scheduler 只读快照
* ``GET  /api/services``              —— 三个受管服务的全景快照（含 mode/vram）
* ``POST /api/services/<name>/start`` —— 启动 llm / tts / comfy
* ``POST /api/services/<name>/stop``  —— 停止 llm / tts / comfy
* ``GET  /api/services/<name>/log``   —— 读日志尾部（?lines=80）
* ``POST /api/mode/<llm|comfy>``      —— 手动切模式
* ``POST /api/generate``              —— 编排式生成（自动切显存，回包后后台恢复）
* ``POST /api/generate/async``        —— 异步出图任务：立即 202，结果落 jobs.json，断线可重查
* ``GET  /api/jobs/<key>``            —— 按 job_id 或 client_job_id 查进度/产物（下载地址按当前 Host 重算）
* ``GET  /api/jobs``                  —— 最近任务列表（?limit=20&status=running,succeeded）
* ``POST /api/photos``                —— 上传参考照片进照片库（长期保存，返回 photo_id）
* ``GET  /api/photos``                —— 照片库列表
* ``GET  /api/photos/<id>``           —— 取照片原图（?download=1 走 attachment）
* ``DELETE /api/photos/<id>``         —— 删除照片
* ``POST /api/prompt``                —— 只构建 API prompt，不执行
* ``GET  /api/workflow`` ``/api/queue``
* ``GET  /files/<filename>``          —— 从输出目录下载图片（ComfyUI 关掉后仍可下）

错误统一返回 JSON：400 参数错误 / 404 未知路径或服务 / 409 编排锁被占 /
413 请求体过大 / 502 编排或 ComfyUI 失败 / 503 连不上 ComfyUI / 504 超时 / 500 未知错误。
"""

from __future__ import annotations

import argparse
import base64
import copy
import hmac
import json
import mimetypes
import os
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, quote, unquote, urlparse

from comfy_client import ComfyClient, ComfyConnectionError, ComfyError, ComfyTimeout
from runner import (
    DEFAULT_BASE_URL,
    DEFAULT_TEMPLATE,
    build_prompt,
    generate,
    load_template,
    parse_size,
)
from service_manager import (
    SERVICE_COMFY,
    SERVICE_LLM,
    ServiceBusy,
    ServiceError,
    ServiceManager,
    default_specs,
)

SERVER_NAME = "ComfyUI-Qwen-Image-Edit-Submitter/2.0"
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
MAX_BODY_BYTES = 32 * 1024 * 1024  # 32MB
DEFAULT_TIMEOUT = 1800.0
# cli.py 自己等 ComfyUI 用 --timeout，子进程再多给这几秒把结果落盘/序列化完
CLI_EXTRA_TIMEOUT = 180.0
# 后台恢复：ServiceBusy 时 1s 退避重试，最多 120 次（≈2 分钟）
RESTORE_MAX_RETRIES = 120
RESTORE_RETRY_INTERVAL = 1.0
GRACE_POLL = 0.25
# 允许的模式（与 service_manager 保持一致）
VALID_MODES = (SERVICE_LLM, SERVICE_COMFY)

# --------------------------------------------------------------------------- #
# 异步出图任务：POST /api/generate/async + GET /api/jobs[/<key>]
# --------------------------------------------------------------------------- #
# 任务状态（终态 = 不会再变）
JOB_STATUS_QUEUED = "queued"
JOB_STATUS_RUNNING = "running"
JOB_STATUS_SUCCEEDED = "succeeded"
JOB_STATUS_FAILED = "failed"
JOB_STATUS_INTERRUPTED = "interrupted"
JOB_STATUSES = (
    JOB_STATUS_QUEUED,
    JOB_STATUS_RUNNING,
    JOB_STATUS_SUCCEEDED,
    JOB_STATUS_FAILED,
    JOB_STATUS_INTERRUPTED,
)
JOB_TERMINAL_STATUSES = (
    JOB_STATUS_SUCCEEDED,
    JOB_STATUS_FAILED,
    JOB_STATUS_INTERRUPTED,
)
# 任务阶段（只用于给客户端展示"卡在哪一步"）
JOB_STAGES = ("queued", "orchestrating", "generating", "finalizing", "done", "failed")
DEFAULT_JOB_RETENTION = 200
DEFAULT_JOB_LIMIT = 20
MAX_JOB_LIMIT = 100
# client_job_id 是客户端自己生成的幂等键：只允许 URL / 日志友好的字符，
# 因为它会出现在 /api/jobs/<key> 路径里。
CLIENT_JOB_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
# jobs.json 是"整表快照"写法：每次状态变化都要重写。阶段切换/终态属于低频事件，
# 一律强制落盘；只有纯粹刷新时间戳这类高频变化才走这个节流窗口。
JOB_PERSIST_MIN_INTERVAL = 2.0

# --------------------------------------------------------------------------- #
# 照片库：服务端长期保存的参考照片（POST/GET/DELETE /api/photos）
# --------------------------------------------------------------------------- #
# 默认落盘目录：<PROJECT_DIR>/photos（--photos-dir 可覆盖）
PHOTOS_DIRNAME = "photos"
# photo_id 会出现在 /api/photos/<id> 路径里，只允许 URL / 日志友好且不可猜的字符
PHOTO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{4,64}$")
# workflow 的 TextEncodeQwenImageEditPlus 只有 image1/image2 两个 LoadImage 槽位，
# 所以一次生成**最多 2 张**参考图（第 3 张会被 runner 建成没人引用的孤儿节点）
MAX_REFERENCE_IMAGES = 2
# 单张照片上限 16MB：手机直出原图够用，又不至于让 /api/photos 变成大文件仓库
PHOTO_MAX_BYTES = 16 * 1024 * 1024


def _is_protected_path(path: str) -> bool:
    """需要令牌的路径：``/api/*`` 与 ``/files/*``。

    ``GET /``（WebUI 自身）不要求令牌——否则用户没有任何入口去录入 ``--token``。
    """
    return (
        path == "/api"
        or path.startswith("/api/")
        or path == "/files"
        or path.startswith("/files/")
    )


# --------------------------------------------------------------------------- #
# 核心逻辑（与 HTTP 解耦，便于单测）
# --------------------------------------------------------------------------- #
class ApiError(Exception):
    """业务层错误，携带 HTTP 状态码。

    ``resource`` 是可选的资源编排快照：生成失败时也要让调用方看到
    "资源模式 / 后台恢复是否已排上"，所以由 handle_generate 附加、_dispatch 透出。
    """

    def __init__(
        self,
        status: int,
        message: str,
        detail: Any = None,
        resource: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.message = message
        self.detail = detail
        self.resource = resource


class OutputDirError(OSError):
    """输出目录不可用：启动阶段报准确文案，不能被当成"无法监听 host:port"。"""


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "y", "on")
    return False


def _as_int(value: Any, field: str) -> int:
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ApiError(400, f"字段 {field} 必须是整数，收到：{value!r}") from exc


def _as_float(value: Any, field: str) -> float:
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise ApiError(400, f"字段 {field} 必须是数字，收到：{value!r}") from exc


def _resolve_images(payload: Dict[str, Any]) -> Optional[List[Any]]:
    """支持 images: [str|obj] 以及单张 image: str。"""
    raw = payload.get("images")
    if raw is None and payload.get("image") is not None:
        raw = [payload["image"]]
    if raw is None:
        return None
    if isinstance(raw, str):
        return [raw]
    if not isinstance(raw, list):
        raise ApiError(400, "images 必须是数组")
    if len(raw) > MAX_REFERENCE_IMAGES:
        raise ApiError(
            400, f"参考图最多 {MAX_REFERENCE_IMAGES} 张，收到 {len(raw)} 张"
        )
    cleaned: List[Any] = []
    for item in raw:
        if isinstance(item, (str, dict)):
            cleaned.append(item)
        else:
            raise ApiError(400, "images 的元素必须是字符串路径/文件名或对象")
    return cleaned


def _tail(text: Optional[str], limit: int = 2000) -> str:
    """取字符串尾部（用于把子进程输出塞进错误 detail）。"""
    value = str(text or "")
    return value[-limit:] if len(value) > limit else value


def _first_line(text: Optional[str], limit: int = 300) -> str:
    """取第一个非空行作为错误摘要（cli.py 的主信息在第一行，后面是提示）。"""
    for line in str(text or "").splitlines():
        stripped = line.strip()
        if stripped:
            return stripped[:limit]
    return ""


def _extract_json_object(text: Optional[str]) -> Optional[Dict[str, Any]]:
    """从可能夹着进度输出的 stdout 里抠出第一个 ``{`` 到最后一个 ``}`` 的 JSON。"""
    body = str(text or "")
    start = body.find("{")
    end = body.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(body[start:end + 1])
    except (json.JSONDecodeError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def _image_ref_from_mapping(item: Dict[str, Any]) -> Optional[str]:
    """从 images 的对象元素里取出"路径/input 文件名"引用。"""
    for key in ("path", "filename", "file", "name"):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return None


def health_payload(client: ComfyClient, ctx: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """GET /api/health 的内容（含只读的 scheduler 快照，绝不加锁）。"""
    app = ctx or {}
    data: Dict[str, Any] = {
        "ok": True,
        "server": SERVER_NAME,
        "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    try:
        stats = client.system_stats()
        system = stats.get("system") or {}
        devices = stats.get("devices") or []
        comfy: Dict[str, Any] = {
            "url": client.base_url,
            "comfyui_version": system.get("comfyui_version"),
            "python_version": system.get("python_version"),
            "pytorch_version": system.get("pytorch_version"),
            "ram_free": system.get("ram_free"),
            "devices": [],
        }
        for device in devices:
            comfy["devices"].append(
                {
                    "name": device.get("name"),
                    "type": device.get("type"),
                    "vram_total": device.get("vram_total"),
                    "vram_free": device.get("vram_free"),
                    "torch_vram_total": device.get("torch_vram_total"),
                    "torch_vram_free": device.get("torch_vram_free"),
                }
            )
        queue = client.queue()
        comfy["queue_running"] = len(queue.get("queue_running") or [])
        comfy["queue_pending"] = len(queue.get("queue_pending") or [])
        data["comfyui"] = comfy
        data["ok"] = True
    except ComfyError as exc:
        data["ok"] = False
        data["comfyui"] = {"url": client.base_url, "error": str(exc)}
    except Exception as exc:  # 兜底：健康检查自己绝不允许 500
        data["ok"] = False
        data["comfyui"] = {"url": client.base_url, "error": f"{type(exc).__name__}: {exc}"}

    # 资源编排的只读快照：service_status / current_mode / busy 都不加锁，可高频轮询
    scheduler: Dict[str, Any] = {
        "enabled": bool(app.get("scheduler_enabled")),
        "mode": "unknown",
        "busy": False,
        "dry_run": False,
        "pending_restore": False,
    }
    manager: Optional[ServiceManager] = app.get("manager")
    if manager is not None:
        try:
            scheduler["mode"] = manager.current_mode()
            scheduler["busy"] = bool(manager.busy())
            scheduler["dry_run"] = bool(manager.dry_run)
        except Exception as exc:
            scheduler["error"] = str(exc)
    restore = app.get("restore_scheduler")
    if restore is not None:
        try:
            scheduler["pending_restore"] = bool(restore.pending())
        except Exception:
            pass
    data["scheduler"] = scheduler
    return data


def services_payload(ctx: Dict[str, Any]) -> Dict[str, Any]:
    """GET /api/services：编排器快照 + server 侧配置（全部只读）。"""
    config: Dict[str, Any] = ctx.get("config") or {}
    manager: Optional[ServiceManager] = ctx.get("manager")
    restore = ctx.get("restore_scheduler")
    if manager is None:
        return {
            "ok": True,
            "mode": "unknown",
            "busy": False,
            "vram": None,
            "services": [],
            "dry_run": bool(config.get("dry_run")),
            "scheduler_enabled": False,
            "auto_start": bool(config.get("auto_start")),
            "restore_target": config.get("restore_target"),
            "restore_mode": config.get("restore_mode"),
            "restore_delay": config.get("restore_delay"),
            "base_url": ctx.get("client").base_url if ctx.get("client") else None,
            "comfy_port": config.get("comfy_port"),
            "llm_port": config.get("llm_port"),
            "tts_port": config.get("tts_port"),
            "generate_timeout": config.get("generate_timeout"),
            "generate_engine": config.get("generate_engine"),
            "pending_restore": False,
            "error": "未启用资源编排（manager=None）",
        }
    snapshot = manager.snapshot()
    snapshot.update(
        {
            "scheduler_enabled": bool(ctx.get("scheduler_enabled")),
            "auto_start": bool(config.get("auto_start")),
            "restore_target": config.get("restore_target"),
            "restore_mode": config.get("restore_mode"),
            "restore_delay": config.get("restore_delay"),
            "base_url": ctx.get("client").base_url if ctx.get("client") else None,
            "comfy_port": config.get("comfy_port"),
            "llm_port": config.get("llm_port"),
            "tts_port": config.get("tts_port"),
            "generate_timeout": config.get("generate_timeout"),
            "generate_engine": config.get("generate_engine"),
            "pending_restore": bool(restore.pending()) if restore is not None else False,
        }
    )
    return snapshot


# --------------------------------------------------------------------------- #
# 挂起恢复调度器：先把图片回给用户，再在后台把显存还给 LLM/TTS
# --------------------------------------------------------------------------- #
def _restore_logger(message: str) -> None:
    try:
        sys.stderr.write("[restore] %s\n" % message)
        sys.stderr.flush()
    except Exception:
        pass


class RestoreScheduler:
    """生成结束后把资源切回 ``target`` 模式的"挂起恢复"调度器。

    为什么需要它：用户要的顺序是「返回图片下载地址 → 退出 ComfyUI 让出显存 →
    重新拉起两个 sh 服务」。LLM 要重载 11GB+ 模型，同步恢复会让 /api/generate
    的响应多等几十秒到几分钟，而这段时间用户其实只需要图片。

    合并策略（避免来回抖动）：

    * ``grace`` 窗口：生成结束后先等 ``grace`` 秒，期间若 ``comfy_wanted`` 被置起
      （又来了新的图像请求），直接放弃本次恢复；
    * ``_generation`` 序号：每次 ``schedule`` 递增并起新线程，旧线程立刻让位，
      因此**真正干活的恢复线程任何时刻只有一个**；
    * ``ServiceBusy``（锁被别的切换/生成占着）→ 睡 1s 重试，最多 ``MAX_RETRIES`` 次；
      其它 ``ServiceError`` 只记日志并结束（机器需要人工看一眼，不能卡死线程）。

    线程内任何异常都被吞掉并写日志：后台恢复失败绝不允许影响 HTTP 请求。
    """

    MAX_RETRIES = RESTORE_MAX_RETRIES
    RETRY_INTERVAL = RESTORE_RETRY_INTERVAL

    def __init__(
        self,
        manager: Optional[ServiceManager],
        *,
        target: str = "llm",
        mode: str = "background",
        grace: float = 3.0,
        logger: Optional[Callable[[str], None]] = None,
    ) -> None:
        self._manager = manager
        self._target = str(target or "none").strip().lower()
        self._mode = "blocking" if str(mode or "").strip().lower() == "blocking" else "background"
        self._grace = max(0.0, float(grace))
        self._logger = logger or _restore_logger
        self._lock = threading.Lock()
        self._wanted = threading.Event()
        self._cancel = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._generation = 0
        self._pending = False

    # -------------------------- 对外接口 -------------------------- #
    @property
    def target(self) -> str:
        return self._target

    @property
    def mode(self) -> str:
        return self._mode

    @property
    def grace(self) -> float:
        return self._grace

    def comfy_wanted(self) -> None:
        """标记"又要用 ComfyUI 了"：每次收到图像生成请求时调用。"""
        self._wanted.set()

    def cancel_pending(self) -> None:
        """取消一切挂起的恢复意图（新请求进来 / 手动切模式 / 进程退出时调用）。"""
        self._cancel.set()
        self._wanted.clear()
        with self._lock:
            self._generation += 1
            self._pending = False

    def pending(self) -> bool:
        """是否还有"待执行/正在执行"的恢复。"""
        with self._lock:
            return bool(self._pending)

    def schedule(self, *, grace: Optional[float] = None) -> bool:
        """调度一次恢复；返回是否真的排上了（target 非法或没有 manager 时 False）。"""
        if self._manager is None or self._target not in VALID_MODES:
            return False
        wait = self._grace if grace is None else max(0.0, float(grace))
        # 本轮生成已经结束：清掉 wanted，否则后台线程会把它当成"新请求"而放弃恢复
        self._wanted.clear()

        if self._mode == "blocking":
            # 阻塞模式：调用方明确接受"多等几十秒"，异常只记日志
            self._cancel.clear()
            try:
                self._manager.ensure_mode(self._target)
                self._log("已同步恢复 %s 模式" % self._target)
                return True
            except Exception as exc:
                self._log("同步恢复 %s 模式失败：%s" % (self._target, exc))
                return False

        with self._lock:
            # 每次 schedule 都递增代际并起一个新的等待线程：旧线程看到代际不匹配会立刻退出
            # （不会调用 ensure_mode），因此任何时刻真正干活的只有一个恢复线程。
            # 之所以不复用旧线程：cancel_pending() 已经让旧线程的代际失效，复用它会让
            # pending 永远卡在 True（没人负责恢复），这里用"新线程接管"消除该竞态。
            self._generation += 1
            gen = self._generation
            self._cancel.clear()
            previous = self._thread
            thread = threading.Thread(
                target=self._run, args=(gen, wait), name="restore-scheduler", daemon=True
            )
            # 先置位再 start：反过来的话，线程可能瞬间跑完并清掉 pending，我们再置 True
            # 就永久卡住了。但 start() 失败必须回滚 pending 并把旧线程放回去——这个
            # finally 里的失败绝不能冒泡覆盖调用方的原始异常。
            self._pending = True
            self._thread = thread
            try:
                thread.start()
            except RuntimeError as exc:
                self._pending = False
                self._thread = previous
                self._log("无法启动恢复线程：%s（本次不调度恢复）" % exc)
                return False
            superseded = previous is not None and previous.is_alive()
        if superseded:
            # 旧线程还在 grace 窗口里等：它会让位，grace 从本次请求重新计时
            self._log("已有恢复线程在等，合并本次请求（grace=%.1fs）" % wait)
        else:
            self._log("已排队恢复 %s 模式（grace=%.1fs）" % (self._target, wait))
        return True

    def shutdown(self) -> None:
        """让后台线程尽快退出（server 收到 SIGTERM / Ctrl+C 时调用）。"""
        self._cancel.set()
        self._wanted.clear()
        with self._lock:
            self._generation += 1
            self._pending = False

    # -------------------------- 内部实现 -------------------------- #
    def _log(self, message: str) -> None:
        try:
            self._logger(message)
        except Exception:
            pass

    def _stale(self, gen: int) -> bool:
        return self._cancel.is_set() or self._generation != gen

    def _run(self, gen: int, wait: float) -> None:
        """后台恢复线程主体：grace 等待 → ensure_mode 重试。异常一律不外泄。"""
        try:
            # 1) grace 窗口：0.25s 粒度轮询，便于及时响应新请求/取消
            deadline = time.monotonic() + max(0.0, float(wait))
            while True:
                if self._stale(gen):
                    self._log("恢复被取消（新的请求或手动切换），放弃本次恢复")
                    return
                if self._wanted.is_set():
                    self._log("grace 窗口内又收到生成请求，跳过本次恢复")
                    return
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                time.sleep(min(GRACE_POLL, left))

            # 2) 到点开始恢复；锁被占（正在生成/切换）时退避重试
            for attempt in range(1, self.MAX_RETRIES + 1):
                if self._stale(gen):
                    self._log("恢复被取消（新的请求或手动切换），放弃本次恢复")
                    return
                if self._wanted.is_set():
                    self._log("恢复前发现新的生成请求，跳过本次恢复")
                    return
                try:
                    result = self._manager.ensure_mode(self._target)  # type: ignore[union-attr]
                    self._log(
                        "已恢复 %s 模式（switched=%s, mode_after=%s）"
                        % (
                            self._target,
                            (result or {}).get("switched"),
                            (result or {}).get("mode_after"),
                        )
                    )
                    return
                except ServiceBusy:
                    if attempt >= self.MAX_RETRIES:
                        self._log("恢复 %s 模式重试 %d 次仍被占用，放弃" % (self._target, attempt))
                        return
                    time.sleep(self.RETRY_INTERVAL)
                except ServiceError as exc:
                    self._log(
                        "恢复 %s 模式失败：%s（detail=%s）"
                        % (self._target, exc, _tail(json.dumps(exc.detail, ensure_ascii=False), 800))
                    )
                    return
                except Exception as exc:  # noqa: BLE001 - 后台线程绝不外泄
                    self._log("恢复 %s 模式异常：%s" % (self._target, exc))
                    return
        except Exception as exc:  # noqa: BLE001 - 兜底
            self._log("后台恢复线程异常：%s" % exc)
        finally:
            with self._lock:
                if self._generation == gen:
                    self._pending = False


# --------------------------------------------------------------------------- #
# 生成执行层：CLI 子进程（默认）或旧的内嵌 runner.generate
# --------------------------------------------------------------------------- #
DATA_URL_PREFIX = "data:"


def _cleanup_temp_files(paths: Optional[List[str]]) -> None:
    """删除临时文件；不存在或删除失败都忽略（清理动作绝不能掩盖原始异常）。"""
    for path in paths or []:
        try:
            os.unlink(path)
        except OSError:
            pass


def _write_temp_image(blob: bytes, prefix: str = "comfy_ref_") -> str:
    """把图片字节落到临时文件，返回绝对路径（删除由调用方负责）。"""
    fd, path = tempfile.mkstemp(prefix=prefix, suffix=".png")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(blob)
    except BaseException:
        _cleanup_temp_files([path])   # 写一半失败也不留空文件
        raise
    return path


def _decode_data_url(text: str) -> bytes:
    """解码 ``data:image/png;base64,....``，非法时抛 400。"""
    head, _, body = text.partition(",")
    if not body.strip():
        raise ApiError(400, "data URL 缺少逗号后的 base64 内容")
    if ";base64" not in head.lower():
        raise ApiError(400, "只支持 base64 编码的 data URL（data:image/png;base64,...）")
    try:
        blob = base64.b64decode(body.strip(), validate=False)
    except Exception as exc:  # noqa: BLE001 - binascii.Error 等
        raise ApiError(400, f"images 中的 base64 无法解码：{exc}") from exc
    if not blob:
        raise ApiError(400, "images 中的 base64 解码后为空")
    return blob


def _prepare_cli_images(images: Optional[List[Any]]) -> Tuple[List[str], List[str]]:
    """把 images 归一成 cli.py ``-i`` 的值；base64 / data URL 元素落成临时文件。

    返回 ``(引用列表, 临时文件列表)``。**函数内部任何一步抛错都会先清掉已建的
    临时文件再重抛**，调用方另在 ``finally`` 里兜底删除，保证 /tmp 不泄漏。
    """
    refs: List[str] = []
    temps: List[str] = []
    try:
        for item in images or []:
            if isinstance(item, str):
                text = item.strip()
                if not text:
                    continue
                if text.startswith(DATA_URL_PREFIX):
                    # 纯字符串形式的 data URL 也必须落盘：几十 KB 以上的 base64
                    # 直接进 argv 会撞 ARG_MAX（OSError: Argument list too long）
                    path = _write_temp_image(_decode_data_url(text))
                    temps.append(path)
                    refs.append(path)
                    continue
                refs.append(text)
                continue
            if not isinstance(item, dict):
                raise ApiError(400, "images 的元素必须是字符串路径/文件名或对象")
            ref = _image_ref_from_mapping(item)
            if ref is not None and not str(ref).startswith(DATA_URL_PREFIX):
                refs.append(ref)
                continue
            # {"base64": ...} / {"data_url": ...} / {"filename": "data:..."} 都走解码 → 落盘
            raw = ref if ref is not None else (
                item.get("base64") or item.get("data_url") or item.get("data")
            )
            if not isinstance(raw, str) or not raw.strip():
                raise ApiError(400, 'images 的元素缺少 path/filename/base64 字段')
            data = raw.strip()
            if data.startswith(DATA_URL_PREFIX):
                blob = _decode_data_url(data)
            else:
                try:
                    blob = base64.b64decode(data, validate=False)
                except Exception as exc:  # noqa: BLE001 - binascii.Error 等
                    raise ApiError(400, f"images 中的 base64 无法解码：{exc}") from exc
                if not blob:
                    raise ApiError(400, "images 中的 base64 解码后为空")
            path = _write_temp_image(blob)
            temps.append(path)
            refs.append(path)
        return refs, temps
    except BaseException:
        _cleanup_temp_files(temps)
        raise


def _build_cli_argv(ctx: Dict[str, Any], params: Dict[str, Any], refs: List[str]) -> List[str]:
    """把 /api/generate 的参数映射成 cli.py 的 argv（逐项对应，不做猜测）。"""
    argv: List[str] = [
        sys.executable,
        os.path.join(PROJECT_DIR, "cli.py"),
        "-p",
        str(params["prompt"]),
        "-o",
        str(ctx.get("output_dir") or "./outputs"),
        "--json",
        "-q",
        "--timeout",
        str(float(params.get("timeout") or DEFAULT_TIMEOUT)),
        "--base-url",
        str(ctx.get("client").base_url if ctx.get("client") else DEFAULT_BASE_URL),
    ]
    template_path = ctx.get("template_path")
    if template_path:
        argv += ["--template", str(template_path)]
    if params.get("negative"):
        argv += ["-n", str(params["negative"])]
    for ref in refs:
        argv += ["-i", ref]
    if params.get("no_reference"):
        argv.append("--no-reference")
    if params.get("seed") is not None:
        argv += ["--seed", str(int(params["seed"]))]
    if params.get("steps") is not None:
        argv += ["--steps", str(int(params["steps"]))]
    if params.get("cfg") is not None:
        argv += ["--cfg", str(float(params["cfg"]))]
    if params.get("sampler"):
        argv += ["--sampler", str(params["sampler"])]
    if params.get("scheduler"):
        argv += ["--scheduler", str(params["scheduler"])]
    if params.get("denoise") is not None:
        argv += ["--denoise", str(float(params["denoise"]))]
    if params.get("width") and params.get("height"):
        # parse_size 已在 handle_generate 里归一化（含 8 对齐），这里只做格式化
        argv += ["--size", "%dx%d" % (int(params["width"]), int(params["height"]))]
    if params.get("batch_size") is not None:
        argv += ["--batch", str(int(params["batch_size"]))]
    if params.get("filename_prefix"):
        argv += ["--filename-prefix", str(params["filename_prefix"])]
    return argv


def _run_cli_generate(ctx: Dict[str, Any], params: Dict[str, Any]) -> Dict[str, Any]:
    """真正调用 ``cli.py --json`` 生成，按退出码映射成 API 错误。"""
    refs: List[str] = []
    temps: List[str] = []
    timeout = float(params.get("timeout") or DEFAULT_TIMEOUT)
    proc: Optional[subprocess.CompletedProcess] = None
    try:
        # 调用点放在 try 内：准备阶段抛错（base64 非法、临时文件写失败）也要走
        # 同一个 finally，不能让 /tmp 里留下一批 comfy_ref_*.png
        refs, temps = _prepare_cli_images(params.get("images"))
        argv = _build_cli_argv(ctx, params, refs)
        try:
            proc = subprocess.run(
                argv,
                cwd=PROJECT_DIR,
                capture_output=True,
                text=True,
                timeout=timeout + CLI_EXTRA_TIMEOUT,
            )
        except subprocess.TimeoutExpired as exc:
            raise ApiError(
                504,
                "生成超时（cli.py 超过 %.0f 秒未返回）" % (timeout + CLI_EXTRA_TIMEOUT),
                {"timeout": timeout, "argv": argv[:6] + ["..."]},
            ) from exc
        except OSError as exc:
            raise ApiError(502, f"无法启动 cli.py：{exc}", {"argv": argv[:6]}) from exc
    finally:
        # 无论成功、失败还是超时，base64 参考图落下的临时文件都要删掉
        _cleanup_temp_files(temps)

    assert proc is not None  # 仅用于类型收窄；上面分支不会落到这里
    stdout = proc.stdout or ""
    stderr = proc.stderr or ""
    detail = {
        "returncode": proc.returncode,
        "stdout_tail": _tail(stdout),
        "stderr_tail": _tail(stderr),
    }
    message = _first_line(stderr) or _first_line(stdout) or "cli.py 未输出错误信息"

    if proc.returncode != 0:
        code = int(proc.returncode)
        if code == 2:
            raise ApiError(400, f"参数错误：{message}", detail)
        if code == 3:
            raise ApiError(504, f"等待 ComfyUI 完成超时：{message}", detail)
        if code == 4:
            raise ApiError(503, f"无法连接 ComfyUI：{message}", detail)
        raise ApiError(502, f"生成失败（cli.py 退出码 {code}）：{message}", detail)

    data = _extract_json_object(stdout)
    if data is None:
        raise ApiError(502, "cli.py 退出码为 0，但输出里没有合法 JSON 结果", detail)
    if not data.get("images"):
        # 退出码 0 但没图：属于业务失败，仍给 502 便于前端直接展示
        raise ApiError(502, "生成结束但没有返回任何图片", detail)
    return data


def _run_internal_generate(ctx: Dict[str, Any], params: Dict[str, Any]) -> Dict[str, Any]:
    """旧行为：进程内直接调 ``runner.generate``（--generate-engine internal）。"""
    try:
        return generate(
            params["prompt"],
            negative=params.get("negative"),
            images=params.get("images"),
            output_dir=str(ctx.get("output_dir") or "./outputs"),
            base_url=str(ctx.get("client").base_url if ctx.get("client") else DEFAULT_BASE_URL),
            template=ctx.get("template"),
            client=ctx.get("client"),
            no_reference=bool(params.get("no_reference")),
            seed=params.get("seed"),
            steps=params.get("steps"),
            cfg=params.get("cfg"),
            sampler=params.get("sampler"),
            scheduler=params.get("scheduler"),
            denoise=params.get("denoise"),
            width=params.get("width"),
            height=params.get("height"),
            batch_size=params.get("batch_size"),
            filename_prefix=params.get("filename_prefix"),
            timeout=float(params.get("timeout") or DEFAULT_TIMEOUT),
        )
    except ComfyTimeout as exc:
        raise ApiError(504, f"等待 ComfyUI 完成超时：{exc}", exc.detail) from exc
    except ComfyConnectionError as exc:
        raise ApiError(503, f"无法连接 ComfyUI：{exc}", None) from exc
    except ComfyError as exc:
        raise ApiError(502, f"ComfyUI 执行失败：{exc}", exc.detail) from exc
    except FileNotFoundError as exc:
        raise ApiError(400, str(exc)) from exc
    except ValueError as exc:
        raise ApiError(400, str(exc)) from exc


def run_generate(ctx: Dict[str, Any], params: Dict[str, Any]) -> Dict[str, Any]:
    """按 ``--generate-engine`` 分发；两种引擎返回同形状的 runner 结果。"""
    engine = str((ctx.get("config") or {}).get("generate_engine") or "cli").strip().lower()
    if engine == "internal":
        return _run_internal_generate(ctx, params)
    return _run_cli_generate(ctx, params)


# Host 头白名单：hostname / IPv4 / [IPv6] + 可选端口。
# 绝不允许引号、斜杠、@、空白、尖括号 —— 这些既是下载地址劫持入口，也是 </script> 注入源。
HOST_HEADER_RE = re.compile(
    r"^(?:\[[0-9A-Fa-f:.]{1,45}\]|[A-Za-z0-9][A-Za-z0-9.\-]{0,252})(?::\d{1,5})?$"
)


def _host_header_ok(value: str) -> bool:
    """校验 Host 头是否形如 ``host[:port]``（IPv6 用方括号）。"""
    text = str(value or "").strip()
    if not text or not HOST_HEADER_RE.match(text):
        return False
    head, sep, port = text.rpartition(":")
    if sep and not head.endswith("]") and port.isdigit():
        if not 0 < int(port) <= 65535:
            return False
    return True


def _public_base(host_header: Optional[str], ctx: Dict[str, Any]) -> str:
    """下载地址用的外部基址。

    优先级：**校验通过**的请求 ``Host`` 头 → ``--public-base-url`` → 监听地址。
    Host 头完全由客户端控制，直接拼进下载地址会同时带来两个问题：下载域名被劫持、
    以及 ``json.dumps`` 不转义 ``</`` 导致 ``</script>`` 注入，所以必须先过白名单。
    """
    host = str(host_header or "").strip()
    if host and _host_header_ok(host):
        return "http://%s" % host
    if host:
        sys.stderr.write("[warn] 忽略非法 Host 头 %r，回退到配置的下载基址\n" % host[:120])
    configured = ctx.get("public_base_url")
    if configured:
        return str(configured).rstrip("/")
    return "http://%s:%d" % (ctx.get("host") or "127.0.0.1", int(ctx.get("port") or 8080))


def _normalize_images(
    result: Dict[str, Any], *, public_base: str, return_base64: bool
) -> List[Dict[str, Any]]:
    """把 runner/cli 的 images 补上本服务的下载地址（ComfyUI 关掉后 view url 会失效）。

    关键点：``/files/<name>`` 服务的是**落盘文件**，而 ``comfy_client._unique_path``
    在重名时会写成 ``xxx_1.png``，所以下载名必须取 ``path`` 的 basename；
    ComfyUI 原始文件名只作为 ``comfy_filename`` 保留用于追溯。
    若沿用原始名，ComfyUI 重启后 SaveImage 计数归零、再次生成同名文件时，
    ``download_url`` 会指回上一次的旧图。
    """
    images_out: List[Dict[str, Any]] = []
    base = public_base.rstrip("/")
    for item in result.get("images") or []:
        if not isinstance(item, dict):
            continue
        path = item.get("path") or item.get("saved_path")
        saved_path = str(path) if path else None
        comfy_filename = item.get("filename")
        # 落盘名优先；path 缺失（老格式/异常输入）时退回 ComfyUI 原名，不崩
        saved_name = os.path.basename(saved_path) if saved_path else None
        filename = saved_name or comfy_filename
        if saved_path and os.path.isfile(saved_path):
            try:
                size_bytes = os.path.getsize(saved_path)
            except OSError:
                size_bytes = item.get("size_bytes")
        else:
            size_bytes = item.get("size_bytes")
        entry: Dict[str, Any] = {
            "filename": filename,
            "comfy_filename": comfy_filename,
            # 本地下载的是扁平落在 output_dir 里的副本，subfolder/type 以落盘文件为准
            "subfolder": "" if saved_name else item.get("subfolder"),
            "type": "output" if saved_name else item.get("type"),
            "url": item.get("url"),
            "saved_path": saved_path,
            "size_bytes": size_bytes,
        }
        if filename:
            download = "%s/files/%s" % (base, quote(str(filename), safe=""))
            entry["download_url"] = download
            entry["download_url_attachment"] = download + "?download=1"
        if return_base64 and saved_path and os.path.isfile(saved_path):
            try:
                with open(saved_path, "rb") as fh:
                    entry["base64"] = base64.b64encode(fh.read()).decode("ascii")
            except OSError as exc:
                entry["base64_error"] = str(exc)
        images_out.append(entry)
    return images_out


# --------------------------------------------------------------------------- #
# 异步任务表：内存索引 + <state-dir>/jobs.json 原子持久化
# --------------------------------------------------------------------------- #
def _log_jobs(message: str) -> None:
    """任务子系统的日志（写 stderr，与 server 其它日志一致）。"""
    try:
        sys.stderr.write("[jobs] %s\n" % message)
        sys.stderr.flush()
    except Exception:
        pass


def _to_timestamp(value: Any, default: Optional[float] = None) -> Optional[float]:
    """把 JSON 里的时间戳转成 float；非法/缺失返回 default（绝不抛）。"""
    if isinstance(value, bool) or value is None:
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _coerce_job_record(item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """把 jobs.json 里的一条原始记录补成完整记录；缺 job_id 的坏记录返回 None。"""
    job_id = item.get("job_id")
    if not isinstance(job_id, str) or not job_id.strip():
        return None
    status = item.get("status")
    if status not in JOB_STATUSES:
        status = JOB_STATUS_QUEUED
    stage = item.get("stage")
    if stage not in JOB_STAGES:
        stage = "queued"
    raw_images = item.get("images")
    images: List[Dict[str, Any]] = []
    if isinstance(raw_images, list):
        images = [entry for entry in raw_images if isinstance(entry, dict)]
    client_job_id = item.get("client_job_id")
    now = time.time()
    created_at = _to_timestamp(item.get("created_at"), now)
    return {
        "job_id": job_id.strip(),
        "client_job_id": client_job_id if isinstance(client_job_id, str) and client_job_id else None,
        "status": status,
        "stage": stage,
        "created_at": created_at,
        "started_at": _to_timestamp(item.get("started_at")),
        "finished_at": _to_timestamp(item.get("finished_at")),
        "updated_at": _to_timestamp(item.get("updated_at"), created_at),
        "timeout_sec": _to_timestamp(item.get("timeout_sec"), DEFAULT_TIMEOUT),
        "prompt_id": item.get("prompt_id"),
        "images": images,
        "error": item.get("error"),
        "http_status": item.get("http_status"),
        "detail": item.get("detail"),
        "resource": item.get("resource"),
        "meta": item.get("meta"),
        "request": item.get("request") if isinstance(item.get("request"), dict) else {},
    }


class JobStore:
    """异步出图任务表：内存索引 + ``<state-dir>/jobs.json`` 原子持久化。

    设计要点：

    * **一把 RLock 管全部读写**。HTTP 线程（提交 / 查询）与 worker 线程（阶段推进 /
      写终态）会并发进来，而任务量只有几十到几百条，用"整表锁"换"绝不会读到半截
      状态"是划算的。
    * **持久化是整表快照，不是流水账**。每次状态变化把整表写成 JSON（同目录临时
      文件 → ``os.replace``），因此 jobs.json 任何时刻都是完整可解析的文件，进程被
      kill 最多丢最后一次变化（而那一次会被重启时的 interrupted 修复兜住）。
    * **重启语义**：加载时把残留的 ``queued``/``running`` 一律标成 ``interrupted``
      ——干活的是 daemon 线程，进程没了线程就没了，留着 running 只会让客户端永远
      轮询下去。
    * **终态有上限**：只按 ``created_at`` 淘汰最旧的终态任务，运行中的任务永不淘汰，
      ``output_dir`` 里的图片文件一个都不删（用户可能还要手动去拿）。
    """

    PERSIST_MIN_INTERVAL = JOB_PERSIST_MIN_INTERVAL

    def __init__(
        self,
        state_dir: str,
        *,
        retention: int = DEFAULT_JOB_RETENTION,
        logger: Optional[Callable[[str], None]] = None,
    ) -> None:
        self._state_dir = os.path.abspath(str(state_dir or "./state"))
        self._path: Optional[str] = os.path.join(self._state_dir, "jobs.json")
        self._retention = int(retention)
        self._logger = logger or _log_jobs
        self._lock = threading.RLock()
        self._jobs: Dict[str, Dict[str, Any]] = {}
        self._by_client: Dict[str, str] = {}
        self._last_persist = 0.0
        self._dirty = False
        self.load()

    # -------------------------- 只读属性 -------------------------- #
    def _log(self, message: str) -> None:
        try:
            self._logger(message)
        except Exception:   # noqa: BLE001 - 日志失败绝不能影响任务流转
            pass

    @property
    def state_dir(self) -> str:
        return self._state_dir

    @property
    def path(self) -> Optional[str]:
        return self._path

    @property
    def retention(self) -> int:
        return self._retention

    def __len__(self) -> int:
        with self._lock:
            return len(self._jobs)

    # -------------------------- 加载 / 落盘 -------------------------- #
    def load(self) -> None:
        """从 jobs.json 装载任务表，并修复上次进程留下的半截状态。"""
        with self._lock:
            self._jobs.clear()
            self._by_client.clear()
            changed = False
            for item in self._read_file():
                record = _coerce_job_record(item)
                if record is None:
                    changed = True   # 坏记录被丢掉，值得重写一次干净的表
                    continue
                job_id = record["job_id"]
                if job_id in self._jobs:   # 同 id 重复：后出现的覆盖，但索引保持一致
                    changed = True
                self._jobs[job_id] = record
                cid = record.get("client_job_id")
                if cid:
                    self._by_client[cid] = job_id

            # 上次进程没跑完的任务：worker 线程已经随进程消失，只能标中断。
            # 注意 stage 保持原样（queued/orchestrating/generating/finalizing 都在
            # 枚举里），客户端据此能知道"停在哪一步"。
            now = time.time()
            for record in self._jobs.values():
                if record.get("status") not in (JOB_STATUS_QUEUED, JOB_STATUS_RUNNING):
                    continue
                record["status"] = JOB_STATUS_INTERRUPTED
                record["error"] = "服务重启，任务已中断"
                record["http_status"] = None
                record["detail"] = None
                record["finished_at"] = now
                record["updated_at"] = now
                changed = True

            if self._prune_locked():
                changed = True
            if changed:
                # 修复结果必须落盘，否则下次重启还会被当成 running 再修一遍
                self._commit_locked(force=True)
            else:
                self._log("任务表已加载：%d 条（%s）" % (len(self._jobs), self._path))

    def _read_file(self) -> List[Dict[str, Any]]:
        path = self._path
        if not path or not os.path.exists(path):
            return []
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError) as exc:
            # 任务表坏了绝不能让服务起不来：挪走坏文件，按空表启动
            self._log("jobs.json 无法读取/解析（%s），按空任务表启动" % exc)
            self._quarantine_bad_file()
            return []
        items: Any = None
        if isinstance(data, dict):
            items = data.get("jobs")
        elif isinstance(data, list):   # 兼容手写/更早的裸数组格式
            items = data
        if not isinstance(items, list):
            self._log("jobs.json 结构非法（缺少 jobs 数组），按空任务表启动")
            self._quarantine_bad_file()
            return []
        return [item for item in items if isinstance(item, dict)]

    def _quarantine_bad_file(self) -> None:
        path = self._path
        if not path:
            return
        bad = path + ".bad"
        try:
            os.replace(path, bad)
            self._log("已把损坏的任务表重命名为 %s" % bad)
        except OSError as exc:
            self._log("重命名损坏任务表失败：%s（继续以空表启动）" % exc)

    def _commit_locked(self, *, force: bool = False) -> None:
        """把整表写盘（调用方必须已持锁）。

        新建/阶段切换/终态都是低频事件，一律 ``force=True`` 立即落盘；``force=False``
        走 ``PERSIST_MIN_INTERVAL`` 节流窗口，留给"只刷新时间戳"这类高频变化使用
        （本服务目前不在 worker 里心跳刷盘，所以实际上不会丢状态）。
        """
        if not self._path:
            self._dirty = False
            return
        now = time.monotonic()
        if not force and (now - self._last_persist) < self.PERSIST_MIN_INTERVAL:
            self._dirty = True
            return
        self._write_locked()
        self._dirty = False
        self._last_persist = time.monotonic()

    def _write_locked(self) -> None:
        path = self._path
        if not path:
            return
        tmp_path: Optional[str] = None
        try:
            os.makedirs(self._state_dir, exist_ok=True)
            payload = {
                "version": 1,
                "saved_at": time.time(),
                "jobs": sorted(
                    self._jobs.values(),
                    key=lambda r: (float(r.get("created_at") or 0.0), str(r.get("job_id") or "")),
                ),
            }
            fd, tmp_path = tempfile.mkstemp(prefix="jobs.json.", suffix=".tmp", dir=self._state_dir)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
                fh.flush()
                os.fsync(fh.fileno())   # 先落盘再 replace，避免断电留下空文件
            os.replace(tmp_path, path)
            tmp_path = None            # 已改名成功，不再清理
        except OSError as exc:
            self._log("任务表落盘失败：%s（内存状态仍然有效，重启后会丢失）" % exc)
        finally:
            if tmp_path:
                _cleanup_temp_files([tmp_path])

    def flush(self) -> None:
        """强制把内存里的任务表写盘（测试与退出前用）。"""
        with self._lock:
            self._commit_locked(force=True)

    # -------------------------- 写操作 -------------------------- #
    def create_or_get(
        self,
        *,
        client_job_id: Optional[str],
        request: Optional[Dict[str, Any]],
        timeout_sec: float,
    ) -> Tuple[Dict[str, Any], bool]:
        """创建任务；``client_job_id`` 已存在时返回既有任务（``created=False``）。

        幂等判定与插入在**同一把锁**里完成，因此两个并发 POST 用同一个
        ``client_job_id`` 时只会有一个任务被建出来。
        """
        with self._lock:
            if client_job_id:
                existing_id = self._by_client.get(client_job_id)
                if existing_id and existing_id in self._jobs:
                    return copy.deepcopy(self._jobs[existing_id]), False
            now = time.time()
            job_id = self._new_job_id_locked()
            record: Dict[str, Any] = {
                "job_id": job_id,
                "client_job_id": client_job_id or None,
                "status": JOB_STATUS_QUEUED,
                "stage": "queued",
                "created_at": now,
                "started_at": None,
                "finished_at": None,
                "updated_at": now,
                "timeout_sec": float(timeout_sec or 0.0),
                "prompt_id": None,
                "images": [],
                "error": None,
                "http_status": None,
                "detail": None,
                "resource": None,
                "meta": None,
                "request": dict(request or {}),
            }
            self._jobs[job_id] = record
            if client_job_id:
                self._by_client[client_job_id] = job_id
            self._commit_locked(force=True)
            return copy.deepcopy(record), True

    def _new_job_id_locked(self) -> str:
        """短 id：``20261123T122911-ab12cd34ef``（时间前缀便于人工看，随机段避免撞号）。"""
        while True:
            job_id = "%s-%s" % (time.strftime("%Y%m%dT%H%M%S"), os.urandom(5).hex())
            if job_id not in self._jobs:
                return job_id

    def mark_running(self, job_id: str) -> None:
        with self._lock:
            record = self._jobs.get(str(job_id or ""))
            if record is None or record.get("status") not in (JOB_STATUS_QUEUED, JOB_STATUS_RUNNING):
                return
            now = time.time()
            record["status"] = JOB_STATUS_RUNNING
            record["started_at"] = record.get("started_at") or now
            record["updated_at"] = now
            self._commit_locked(force=True)

    def set_stage(self, job_id: str, stage: str) -> None:
        """推进阶段；对未知阶段、已终态任务、重复阶段都是安全的 no-op。"""
        if stage not in JOB_STAGES:
            return
        with self._lock:
            record = self._jobs.get(str(job_id or ""))
            if record is None:
                return
            if record.get("status") not in (JOB_STATUS_QUEUED, JOB_STATUS_RUNNING):
                return   # 迟到的进度回调绝不能把终态改回 running
            if record.get("stage") == stage:
                return
            now = time.time()
            record["stage"] = stage
            record["updated_at"] = now
            if record.get("started_at") is None and stage != "queued":
                record["started_at"] = now
            self._commit_locked(force=True)

    def finish_success(
        self,
        job_id: str,
        *,
        prompt_id: Any = None,
        images: Optional[List[Dict[str, Any]]] = None,
        meta: Any = None,
        resource: Any = None,
    ) -> Optional[Dict[str, Any]]:
        with self._lock:
            record = self._jobs.get(str(job_id or ""))
            if record is None:
                return None
            if record.get("status") in JOB_TERMINAL_STATUSES:
                return copy.deepcopy(record)   # 终态先到先得，后来的结果一律忽略
            now = time.time()
            record["status"] = JOB_STATUS_SUCCEEDED
            record["stage"] = "done"
            record["finished_at"] = now
            record["updated_at"] = now
            record["prompt_id"] = prompt_id
            record["images"] = [dict(entry) for entry in images or [] if isinstance(entry, dict)]
            if meta is not None:
                record["meta"] = meta
            if resource is not None:
                record["resource"] = resource
            record["error"] = None
            record["http_status"] = None
            record["detail"] = None
            self._prune_locked()
            self._commit_locked(force=True)
            return copy.deepcopy(record)

    def finish_failure(
        self,
        job_id: str,
        *,
        error: str,
        http_status: Optional[int] = 500,
        detail: Any = None,
        resource: Any = None,
    ) -> Optional[Dict[str, Any]]:
        with self._lock:
            record = self._jobs.get(str(job_id or ""))
            if record is None:
                return None
            if record.get("status") in JOB_TERMINAL_STATUSES:
                return copy.deepcopy(record)
            now = time.time()
            record["status"] = JOB_STATUS_FAILED
            record["stage"] = "failed"
            record["finished_at"] = now
            record["updated_at"] = now
            record["error"] = str(error or "未知错误")
            record["http_status"] = int(http_status) if http_status is not None else None
            record["detail"] = detail
            record["images"] = []
            if resource is not None:
                record["resource"] = resource
            self._prune_locked()
            self._commit_locked(force=True)
            return copy.deepcopy(record)

    def _prune_locked(self) -> bool:
        """按保留上限淘汰最旧的终态任务；返回是否真的删了东西。"""
        if self._retention <= 0:
            return False
        terminal = [r for r in self._jobs.values() if r.get("status") in JOB_TERMINAL_STATUSES]
        if len(terminal) <= self._retention:
            return False
        terminal.sort(key=lambda r: (float(r.get("created_at") or 0.0), str(r.get("job_id") or "")))
        dropped = terminal[: len(terminal) - self._retention]
        for record in dropped:
            job_id = record.get("job_id")
            self._jobs.pop(job_id, None)
            cid = record.get("client_job_id")
            if cid and self._by_client.get(cid) == job_id:
                self._by_client.pop(cid, None)
        self._log("已按保留上限 %d 淘汰 %d 条终态任务（图片文件未删除）"
                  % (self._retention, len(dropped)))
        return True

    # -------------------------- 读操作 -------------------------- #
    def get(self, job_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            record = self._jobs.get(str(job_id or ""))
            return copy.deepcopy(record) if record is not None else None

    def get_by_client_id(self, client_job_id: Optional[str]) -> Optional[Dict[str, Any]]:
        cid = str(client_job_id or "")
        if not cid:
            return None
        with self._lock:
            job_id = self._by_client.get(cid)
            record = self._jobs.get(job_id) if job_id else None
            return copy.deepcopy(record) if record is not None else None

    def resolve(self, key: str) -> Optional[Dict[str, Any]]:
        """按 job_id 查，查不到再按 client_job_id 查（同一次加锁内完成）。"""
        text = str(key or "").strip()
        if not text:
            return None
        with self._lock:
            record = self._jobs.get(text)
            if record is None:
                job_id = self._by_client.get(text)
                record = self._jobs.get(job_id) if job_id else None
            return copy.deepcopy(record) if record is not None else None

    def list_jobs(
        self,
        *,
        limit: int = DEFAULT_JOB_LIMIT,
        statuses: Optional[List[str]] = None,
    ) -> List[Dict[str, Any]]:
        wanted = set(statuses) if statuses else None
        with self._lock:
            records = [
                r for r in self._jobs.values()
                if wanted is None or r.get("status") in wanted
            ]
            records.sort(
                key=lambda r: (float(r.get("created_at") or 0.0), str(r.get("job_id") or "")),
                reverse=True,
            )
            return [copy.deepcopy(r) for r in records[: max(0, int(limit))]]


# --------------------------------------------------------------------------- #
# 照片库：服务端长期保存的参考照片（<photos-dir>/<photo_id>.<ext> + sidecar .json）
# --------------------------------------------------------------------------- #
# 只认这三种格式：ComfyUI 的 LoadImage 也只吃这三种，其余一律按 png 兜底
PHOTO_MIME_BY_EXT = {
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "webp": "image/webp",
}


def _photo_ext_from_filename(filename: Optional[str]) -> Optional[str]:
    """从上传时的原始文件名取扩展名（只认 png/jpg/jpeg/webp，其余返回 None）。"""
    name = str(filename or "").strip().lower()
    stem, _, ext = name.rpartition(".")
    if not stem or not ext:
        return None
    return ext if ext in PHOTO_MIME_BY_EXT else None


def _sniff_image_ext(blob: bytes) -> Optional[str]:
    """按 magic bytes 嗅探图片格式：文件名经常缺失或瞎写，内容才是真相。"""
    if blob.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if blob.startswith(b"\xff\xd8\xff"):
        return "jpg"
    # WEBP = "RIFF" + 4 字节小端长度 + "WEBP"
    if len(blob) >= 12 and blob[0:4] == b"RIFF" and blob[8:12] == b"WEBP":
        return "webp"
    return None


def _photo_display_name(filename: Optional[str], ext: str) -> str:
    """对外展示用的文件名：剥掉路径与换行/引号，缺省用 ``photo.<ext>``。"""
    name = os.path.basename(str(filename or "").strip().replace("\\", "/"))
    name = name.replace('"', "_").replace("\r", "_").replace("\n", "_")
    if not name or name in (".", ".."):
        return "photo.%s" % ext
    return name[:128]


class PhotoStore:
    """照片库：**长期保存**的参考照片，用 ``photo_id`` 索引。

    为什么长期保存：助手「全身照片」只需上传一次，之后每次出图都由服务端按 id 取用
    ——客户端不必每轮把几百 KB 的 base64 重新塞进 ``/api/generate`` 的请求体，出图
    参数里只剩一个 16 位十六进制的 id。

    落盘约定（全部在 ``--photos-dir`` 下，默认 ``<PROJECT_DIR>/photos``）：

    * ``<photo_id>.<ext>``      —— 图片本体（png/jpg/webp，按内容嗅探）
    * ``<photo_id>.json``       —— sidecar 元数据（原始文件名 / mime / 大小 / 时间）
    * ``<photo_id>.<ext>.tmp``  —— 原子写的中间文件，正常情况下不会留下

    ``photo_id`` 由 ``os.urandom(8).hex()`` 生成：16 位十六进制，既满足
    ``PHOTO_ID_RE``，又不可枚举——照片库是私密内容，绝不能用自增序号。
    """

    def __init__(self, root: str) -> None:
        self._root = os.path.abspath(
            str(root or os.path.join(PROJECT_DIR, PHOTOS_DIRNAME))
        )
        os.makedirs(self._root, exist_ok=True)

    # -------------------------- 只读属性 -------------------------- #
    @property
    def root(self) -> str:
        return self._root

    def __len__(self) -> int:
        return len(self.list())

    # -------------------------- 落盘工具 -------------------------- #
    def _write_atomic(self, target: str, data: bytes) -> None:
        """先写 ``<target>.tmp`` 再 ``os.replace``：进程被 kill 也不会留下半截图片。"""
        tmp = target + ".tmp"
        try:
            with open(tmp, "wb") as fh:
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())   # 先落盘再改名，避免断电留下空文件
            os.replace(tmp, target)
        except OSError:
            _cleanup_temp_files([tmp])   # 写一半失败也不留垃圾文件
            raise

    def _sidecar_path(self, photo_id: str) -> str:
        return os.path.join(self._root, "%s.json" % photo_id)

    def _write_sidecar(self, info: Dict[str, Any]) -> None:
        """写 sidecar 元数据。

        失败只记日志：图片本体已经在库里了，不能因为"元数据更不了"就把上传判失败；
        ``describe()`` 缺 sidecar 时会用文件系统信息兜底。
        """
        photo_id = str(info.get("photo_id") or "")
        payload = {
            key: info.get(key)
            for key in ("photo_id", "filename", "mime", "size_bytes", "created_at")
        }
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        target = self._sidecar_path(photo_id)
        try:
            self._write_atomic(target, body)
        except OSError as exc:
            sys.stderr.write("[warn] 照片元数据落盘失败（%s）：%s\n" % (target, exc))

    def _read_sidecar(self, photo_id: str) -> Optional[Dict[str, Any]]:
        try:
            with open(self._sidecar_path(photo_id), "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    # -------------------------- 校验 -------------------------- #
    @staticmethod
    def _valid_id(photo_id: str) -> bool:
        """id 白名单校验：``PHOTO_ID_RE`` + basename 同一性（双重防路径穿越）。"""
        key = str(photo_id or "").strip()
        return bool(key) and bool(PHOTO_ID_RE.match(key)) and os.path.basename(key) == key

    # -------------------------- 写操作 -------------------------- #
    def save(self, blob: bytes, filename: Optional[str] = None) -> Dict[str, Any]:
        """把一张照片存进照片库，返回元数据（含 ``photo_id`` 与落盘绝对路径）。"""
        if not blob:
            raise ApiError(400, "照片内容为空")
        if len(blob) > PHOTO_MAX_BYTES:
            raise ApiError(
                413, "照片过大（%d 字节），上限 %d 字节" % (len(blob), PHOTO_MAX_BYTES)
            )
        # 扩展名优先级：原始文件名 > magic bytes > png 兜底
        ext = _photo_ext_from_filename(filename) or _sniff_image_ext(blob) or "png"
        photo_id = os.urandom(8).hex()
        target = os.path.join(self._root, "%s.%s" % (photo_id, ext))
        try:
            self._write_atomic(target, blob)
        except OSError as exc:
            raise ApiError(500, "照片落盘失败：%s" % exc) from exc
        info: Dict[str, Any] = {
            "photo_id": photo_id,
            "filename": _photo_display_name(filename, ext),
            "mime": PHOTO_MIME_BY_EXT.get(ext, "application/octet-stream"),
            "size_bytes": len(blob),
            "created_at": time.time(),
            "path": target,
        }
        self._write_sidecar(info)
        return info

    def delete(self, photo_id: str) -> bool:
        """删除图片本体 + sidecar；照片不存在返回 ``False``（HTTP 层据此回 404）。"""
        key = str(photo_id or "").strip()
        path = self.path_of(key)
        if path is None:
            return False
        removed = True
        try:
            os.unlink(path)
        except OSError:
            removed = False
        for leftover in (self._sidecar_path(key), path + ".tmp"):
            try:
                os.unlink(leftover)
            except OSError:
                pass   # sidecar / 临时文件本来就可能不存在，清不掉也无所谓
        return removed

    # -------------------------- 读操作 -------------------------- #
    def path_of(self, photo_id: str) -> Optional[str]:
        """按 id 找实际文件（``<id>.<ext>``，排除 .json/.tmp），找不到返回 None。"""
        key = str(photo_id or "").strip()
        if not self._valid_id(key):
            return None
        try:
            names = sorted(os.listdir(self._root))
        except OSError:
            return None
        prefix = "%s." % key
        for name in names:
            if not name.startswith(prefix):
                continue
            suffix = name[len(prefix):]
            if not suffix or suffix == "json" or suffix.endswith(".tmp"):
                continue
            candidate = os.path.join(self._root, name)
            if os.path.isfile(candidate):
                return os.path.abspath(candidate)
        return None

    def describe(self, photo_id: str) -> Optional[Dict[str, Any]]:
        """返回 sidecar 元数据；sidecar 缺失时用文件系统信息兜底。"""
        key = str(photo_id or "").strip()
        path = self.path_of(key)
        if path is None:
            return None
        meta = self._read_sidecar(key) or {}
        ext = os.path.splitext(path)[1].lstrip(".").lower()
        try:
            fs_size = os.path.getsize(path)
            fs_mtime = os.path.getmtime(path)
        except OSError:
            fs_size, fs_mtime = None, None
        return {
            "photo_id": key,
            "filename": meta.get("filename") or os.path.basename(path),
            "mime": meta.get("mime") or PHOTO_MIME_BY_EXT.get(ext, "application/octet-stream"),
            "size_bytes": meta.get("size_bytes") or fs_size,
            "created_at": meta.get("created_at") or fs_mtime,
            "path": path,
        }

    def list(self) -> List[Dict[str, Any]]:
        """按 ``created_at`` 倒序列出所有照片（每项含 photo_id/filename/大小/时间）。"""
        try:
            names = sorted(os.listdir(self._root))
        except OSError:
            return []
        photos: List[Dict[str, Any]] = []
        seen: set = set()
        for name in names:
            # sidecar 与临时文件都不是"一张照片"，跳过；其余按 <id>.<ext> 取 id
            if name.endswith(".json") or name.endswith(".tmp"):
                continue
            stem = name.rsplit(".", 1)[0]
            if stem in seen or not self._valid_id(stem):
                continue
            info = self.describe(stem)
            if info is None:
                continue
            seen.add(stem)
            photos.append(info)
        photos.sort(
            key=lambda item: (float(item.get("created_at") or 0.0), str(item.get("photo_id"))),
            reverse=True,
        )
        return photos


def _job_images_raw(result: Dict[str, Any]) -> List[Dict[str, Any]]:
    """把 handle_generate 归一化后的 images 裁成"落盘事实"，供 jobs.json 持久化。

    只保留与下载地址**无关**的字段（落盘路径 / ComfyUI 原名 / 大小）。``download_url``
    绝不入库：它带主机名，而客户端换了网络（Wi-Fi → 4G）后 IP 会变，必须每次查询时
    按当前请求的 Host 重新算。
    """
    raw: List[Dict[str, Any]] = []
    for item in (result or {}).get("images") or []:
        if not isinstance(item, dict):
            continue
        raw.append({
            "path": item.get("saved_path") or item.get("path"),
            "filename": item.get("comfy_filename") or item.get("filename"),
            "subfolder": item.get("subfolder"),
            "type": item.get("type"),
            "url": item.get("url"),
            "size_bytes": item.get("size_bytes"),
        })
    return raw


def _job_request_summary(params: Dict[str, Any]) -> Dict[str, Any]:
    """任务表里保存的请求摘要：**不含**参考图 base64（那是几 MB 的垃圾数据）。"""
    width, height = params.get("width"), params.get("height")
    size = "%dx%d" % (int(width), int(height)) if width and height else None
    summary = {
        "prompt": params.get("prompt"),
        "negative": params.get("negative"),
        "size": size,
        "steps": params.get("steps"),
        "seed": params.get("seed"),
        "batch_size": params.get("batch_size"),
        "has_reference_images": bool(params.get("images")),
        "no_reference": bool(params.get("no_reference")),
    }
    # 只有真的带了照片才写这个键：不带照片的请求摘要与改造前逐字节一致
    if params.get("full_body_photo_id"):
        summary["full_body_photo_id"] = params.get("full_body_photo_id")
    return summary


def _job_snapshot(
    record: Dict[str, Any],
    *,
    public_base: str,
    now: Optional[float] = None,
    compact: bool = False,
) -> Dict[str, Any]:
    """把任务记录转成对外快照（**每次查询都重算下载地址**）。

    ``compact=True`` 给列表接口用：省掉 ``detail`` 与 ``request.prompt`` 以控体积。
    """
    moment = time.time() if now is None else float(now)
    created_at = _to_timestamp(record.get("created_at"), 0.0) or 0.0
    started_at = _to_timestamp(record.get("started_at"))
    finished_at = _to_timestamp(record.get("finished_at"))
    status = str(record.get("status") or JOB_STATUS_QUEUED)
    begin = started_at if started_at is not None else created_at
    end = finished_at if finished_at is not None else moment
    elapsed = max(0.0, float(end) - float(begin)) if begin else 0.0

    images: List[Dict[str, Any]] = []
    if status == JOB_STATUS_SUCCEEDED:
        try:
            images = _normalize_images(
                {"images": record.get("images") or []},
                public_base=public_base,
                return_base64=False,
            )
        except Exception as exc:  # noqa: BLE001 - 快照永远不能把查询打成 500
            _log_jobs("重建任务 %s 的下载地址失败：%s" % (record.get("job_id"), exc))
            images = []

    request_summary = dict(record.get("request") or {})
    if compact:
        request_summary.pop("prompt", None)

    return {
        "job_id": record.get("job_id"),
        "client_job_id": record.get("client_job_id"),
        "status": status,
        "stage": record.get("stage"),
        "created_at": created_at,
        "started_at": started_at,
        "finished_at": finished_at,
        "progress": {
            "elapsed_sec": round(elapsed, 2),
            "timeout_sec": _to_timestamp(record.get("timeout_sec"), DEFAULT_TIMEOUT),
        },
        "prompt_id": record.get("prompt_id"),
        "images": images,
        "error": record.get("error"),
        "http_status": record.get("http_status"),
        "detail": None if compact else record.get("detail"),
        "resource": record.get("resource"),
        "meta": record.get("meta"),
        "request": request_summary,
        "status_url": "/api/jobs/%s" % record.get("job_id"),
    }


def _safe_finish_failure(
    store: JobStore,
    job_id: str,
    *,
    error: str,
    http_status: Optional[int],
    detail: Any = None,
    resource: Any = None,
) -> None:
    """写失败终态；连落库都失败时只记日志，绝不允许异常飞出 worker 线程。"""
    try:
        store.finish_failure(
            job_id, error=error, http_status=http_status, detail=detail, resource=resource
        )
    except Exception as exc:  # noqa: BLE001 - 后台线程绝不外泄
        _log_jobs("写入任务 %s 的失败终态时出错：%s" % (job_id, exc))


def run_generate_job(
    ctx: Dict[str, Any], store: JobStore, job_id: str, payload: Dict[str, Any]
) -> None:
    """异步任务的工作线程主体：**任何异常都必须落到任务记录上**，绝不许冒泡。

    这里刻意直接复用 ``handle_generate``：异步与同步走完全相同的校验、编排、
    恢复与错误映射，只有"在哪个线程里跑"和"结果写到哪里"不同。
    """
    try:
        store.mark_running(job_id)
        result = handle_generate(
            payload,
            ctx,
            public_base=_public_base(None, ctx),
            progress=lambda stage: store.set_stage(job_id, stage),
        )
        store.finish_success(
            job_id,
            prompt_id=result.get("prompt_id"),
            images=_job_images_raw(result),
            meta=result.get("meta"),
            resource=result.get("resource"),
        )
    except ApiError as exc:
        # 生成失败的业务语义（400/409/502/503/504…）原样透出，客户端不用猜
        _safe_finish_failure(
            store,
            job_id,
            error=exc.message,
            http_status=exc.status,
            detail=exc.detail,
            resource=exc.resource,
        )
    except Exception as exc:  # noqa: BLE001 - 兜底：绝不让线程异常影响 HTTP 服务
        traceback.print_exc()
        _safe_finish_failure(
            store,
            job_id,
            error="%s: %s" % (type(exc).__name__, exc),
            http_status=500,
        )


def submit_generate_job(
    payload: Dict[str, Any],
    ctx: Dict[str, Any],
    store: JobStore,
) -> Tuple[Dict[str, Any], bool]:
    """创建（或幂等命中）一个异步任务并起 worker；返回 ``(任务快照记录, 是否新建)``。"""
    if not isinstance(payload, dict):
        raise ApiError(400, "请求体必须是 JSON 对象")

    raw_client_id = payload.get("client_job_id")
    client_job_id: Optional[str] = None
    if raw_client_id is not None:
        if not isinstance(raw_client_id, str) or not CLIENT_JOB_ID_RE.match(raw_client_id.strip()):
            raise ApiError(
                400,
                "字段 client_job_id 非法：只允许 1..128 个 [A-Za-z0-9._:-] 字符，收到：%r"
                % (raw_client_id,),
            )
        client_job_id = raw_client_id.strip()

    # 幂等：同一个 client_job_id 已经有任务就直接回快照，连参数校验都跳过
    # （客户端重试时的 body 可能已经和第一次不完全一样，没必要因此回 400）
    if client_job_id:
        existing = store.get_by_client_id(client_job_id)
        if existing is not None:
            return existing, False

    params = prepare_generate_params(payload, ctx)   # 校验必须早于建任务
    record, created = store.create_or_get(
        client_job_id=client_job_id,
        request=_job_request_summary(params),
        timeout_sec=params["timeout"],
    )
    if not created:
        # 并发同名：别的线程先建好了，直接回它的快照，绝不再起第二个 worker
        return record, False

    # return_base64 对异步没意义（图片已落盘，查询时给下载地址），去掉以免白白
    # 在 worker 里把几十 MB 图片读成 base64。
    worker_payload = {key: value for key, value in payload.items() if key != "return_base64"}
    thread = threading.Thread(
        target=run_generate_job,
        args=(ctx, store, record["job_id"], worker_payload),
        name="job-%s" % str(record["job_id"])[:8],
        daemon=True,
    )
    try:
        thread.start()
    except RuntimeError as exc:
        # 线程起不来：任务必须落到终态，否则客户端会一直轮询 queued
        _safe_finish_failure(
            store, record["job_id"], error="无法启动任务线程：%s" % exc, http_status=500
        )
        raise ApiError(500, "无法启动任务线程：%s" % exc) from exc
    # 提交响应固定报 queued（任务刚被创建）。这里刻意**不回读**最新状态：worker 线程
    # 可能已经把任务推成 running，回读会得到"202 却说 running"的自相矛盾快照，而客户端
    # 只需要一个稳定的提交凭据；真实进度走 GET /api/jobs/<key>。
    return record, True


def prepare_generate_params(payload: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    """校验并归一化生成参数（``POST /api/generate`` 与 ``/api/generate/async`` 共用）。

    为什么单独抽一层：异步接口必须在**创建任务之前**就把 400 打回去（否则任务表里
    会积一堆注定失败的任务，客户端还得先拿到 job_id 才知道参数错了），而同步接口
    的错误文案一个字符都不能变。两处共用同一份实现，校验规则才不会慢慢漂移。
    """
    if not isinstance(payload, dict):
        raise ApiError(400, "请求体必须是 JSON 对象")
    prompt = payload.get("prompt")
    if not isinstance(prompt, str) or not prompt.strip():
        raise ApiError(400, "字段 prompt 必填，且必须是非空字符串")

    negative = payload.get("negative")
    if negative is not None and not isinstance(negative, str):
        raise ApiError(400, "字段 negative 必须是字符串")

    images = _resolve_images(payload)
    no_reference = _as_bool(payload.get("no_reference") or payload.get("no_reference_images"))
    if no_reference and images:
        raise ApiError(400, "no_reference=true 与 images 互斥")

    # 助手全身照片：客户端历史叫法不统一，按别名依次兜底取一个（取到第一个非 None 的）
    raw_photo_id: Any = payload.get("full_body_photo_id")
    if raw_photo_id is None:
        raw_photo_id = payload.get("reference_photo_id")
    if raw_photo_id is None:
        raw_photo_id = payload.get("photo_id")
    full_body_photo_id: Optional[str] = None
    if raw_photo_id is not None:
        if not isinstance(raw_photo_id, str):
            raise ApiError(400, "字段 full_body_photo_id 必须是字符串（照片 id）")
        text = raw_photo_id.strip()
        if text:   # 空串按"没传"处理，方便客户端直接把变量塞进来
            if not PHOTO_ID_RE.match(text):
                raise ApiError(
                    400,
                    "照片 id 非法：只允许 4..64 个 [A-Za-z0-9_-] 字符，收到：%r"
                    % (raw_photo_id,),
                )
            full_body_photo_id = text
    if full_body_photo_id and no_reference:
        # 互斥：照片本身就是"参考图"，两者同时为真只能是客户端写错了
        raise ApiError(400, "no_reference=true 与 full_body_photo_id 互斥")

    seed = payload.get("seed")
    if seed is not None:
        seed = _as_int(seed, "seed")

    steps = payload.get("steps")
    if steps is not None:
        steps = _as_int(steps, "steps")
    cfg = payload.get("cfg")
    if cfg is not None:
        cfg = _as_float(cfg, "cfg")
    denoise = payload.get("denoise")
    if denoise is not None:
        denoise = _as_float(denoise, "denoise")

    sampler = payload.get("sampler") or payload.get("sampler_name")
    scheduler = payload.get("scheduler")
    filename_prefix = payload.get("filename_prefix")
    batch_size = payload.get("batch_size")
    if batch_size is not None:
        batch_size = _as_int(batch_size, "batch_size")

    width = payload.get("width")
    height = payload.get("height")
    if payload.get("size") is not None:
        try:
            width, height = parse_size(payload["size"])
        except ValueError as exc:
            raise ApiError(400, str(exc)) from exc
    else:
        if width is not None or height is not None:
            if width is None or height is None:
                raise ApiError(400, "width 与 height 需要同时提供")
            width, height = _as_int(width, "width"), _as_int(height, "height")
            try:
                width, height = parse_size(f"{width}x{height}")
            except ValueError as exc:
                raise ApiError(400, str(exc)) from exc
        else:
            width = height = None

    # 缺省取 --generate-timeout（已存进 ctx["config"]），未配置再落回 DEFAULT_TIMEOUT。
    # 这个值同时决定 cli.py 的 --timeout、编排锁等待（timeout+600）与子进程超时。
    configured_timeout = (ctx.get("config") or {}).get("generate_timeout")
    timeout = payload.get("timeout")
    if timeout is None:
        timeout = float(configured_timeout) if configured_timeout else DEFAULT_TIMEOUT
    else:
        timeout = _as_float(timeout, "timeout")
    if timeout <= 0:
        raise ApiError(400, "timeout 必须大于 0")

    return_base64 = _as_bool(payload.get("return_base64"))

    params: Dict[str, Any] = {
        "prompt": prompt,
        "negative": negative,
        "images": images,
        "no_reference": no_reference,
        "seed": seed,
        "steps": steps,
        "cfg": cfg,
        "sampler": sampler,
        "scheduler": scheduler,
        "denoise": denoise,
        "width": width,
        "height": height,
        "batch_size": batch_size,
        "filename_prefix": filename_prefix,
        "timeout": timeout,
        # 照片库里"助手全身照片"的 id（None = 本次不用参考照片）
        "full_body_photo_id": full_body_photo_id,
    }
    params["return_base64"] = return_base64
    return params


def apply_full_body_photo(params: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    """把「助手全身照片」插到参考图**最前面**（固定占 image1）。

    为什么在这个位置做：照片是服务端长期保存的文件，落地方式与用户本次传来的参考图
    **完全相同**（``params["images"]`` 里的一个本地路径），因此插进列表就自动沿用
    ``_prepare_cli_images`` → ``cli.py -i`` → ``runner.resolve_image_reference``
    （本地文件上传到 ComfyUI ``input/``）的既有链路，runner/cli 一行都不用改。

    **顺序语义**：workflow 的 ``TextEncodeQwenImageEditPlus`` 只有 ``image1`` /
    ``image2`` 两个图片槽位，``runner.build_prompt`` 按列表顺序依次填进 image1、image2，
    所以：**照片固定占 image1**（"长什么样"的锚点），用户本次传入的图顺延占 image2
    （"往哪改"的指令图）。超出 ``MAX_REFERENCE_IMAGES`` 的张数从尾部丢掉，并在返回体
    的 ``dropped`` 里如实报告（绝不静默丢图）。

    照片缺失 / 未启用照片库一律**不报错**：需求是"照片存在才用"，出图不能因为一张
    参考照片丢了就整单失败，只在 ``full_body_photo.reason`` 里说明原因。

    返回 ``params["full_body_photo"]`` 那一份字典（同时写回 params，供响应回显）。
    """
    info: Dict[str, Any] = {"photo_id": None, "used": False, "reason": None}
    raw_id = params.get("full_body_photo_id")
    if not raw_id:
        params["full_body_photo"] = info
        return info

    photo_id = str(raw_id)
    info["photo_id"] = photo_id
    store: Optional[PhotoStore] = ctx.get("photo_store")
    if store is None:
        info["reason"] = "未启用照片库"
        params["full_body_photo"] = info
        return info

    photo_path = store.path_of(photo_id)
    if photo_path is None:
        info["reason"] = "照片不存在"
        params["full_body_photo"] = info
        return info

    images = list(params.get("images") or [])
    merged = [photo_path] + images          # 照片在前 → image1，用户的图顺延到 image2
    kept = merged[:MAX_REFERENCE_IMAGES]
    params["images"] = kept
    params["no_reference"] = False          # 带了照片就一定不是纯文生图
    info.update(
        {
            "used": True,
            "filename": os.path.basename(photo_path),
            "path": photo_path,
            # 被上限截掉的张数：>0 说明客户端传多了，需要如实回显而不是假装没事
            "dropped": max(0, len(merged) - len(kept)),
        }
    )
    params["full_body_photo"] = info
    return info


def handle_generate(
    payload: Dict[str, Any],
    ctx: Dict[str, Any],
    *,
    public_base: Optional[str] = None,
    progress: Optional[Callable[[str], None]] = None,
) -> Dict[str, Any]:
    """处理 POST /api/generate：校验 → 编排（拿显存）→ 生成 → 调度后台恢复。

    ``progress`` 是异步任务用的**可选**阶段回调（``orchestrating`` / ``generating``
    / ``finalizing``）。同步接口不传它，行为与加这个参数之前完全一致；回调自身抛
    的异常会被吞掉并记日志——进度上报失败绝不允许影响正在跑的生成。
    """
    params = prepare_generate_params(payload, ctx)
    # 全身照片必须在**编排/生成之前**并进参数：这样 cli / internal 两种引擎、同步与
    # 异步两条链路都自动生效，runner 与 cli.py 一行都不用改。
    apply_full_body_photo(params, ctx)
    timeout = params["timeout"]
    return_base64 = bool(params.get("return_base64"))

    def _progress(stage: str) -> None:
        if progress is None:
            return
        try:
            progress(stage)
        except Exception as exc:  # noqa: BLE001 - 进度上报绝不能影响生成
            _log_jobs("进度回调失败（stage=%s）：%s" % (stage, exc))

    config: Dict[str, Any] = ctx.get("config") or {}
    manager: Optional[ServiceManager] = ctx.get("manager")
    restore: Optional[RestoreScheduler] = ctx.get("restore_scheduler")
    scheduler_enabled = bool(ctx.get("scheduler_enabled")) and manager is not None

    started = time.time()
    restore_scheduled = False

    def _resource() -> Dict[str, Any]:
        """资源编排快照：成功和失败响应都要带上（失败时也能看到恢复有没有排上）。"""
        try:
            mode = manager.current_mode() if manager is not None else "unknown"
        except Exception:  # noqa: BLE001 - 快照永远不能影响响应
            mode = "unknown"
        return {
            "mode": mode,
            "scheduler_enabled": scheduler_enabled,
            "restore_target": config.get("restore_target"),
            "restore_mode": config.get("restore_mode"),
            "restore_scheduled": bool(restore_scheduled),
            # 是否真的进入了编排会话（False 且 restore_scheduled=False 只可能是 409）
            "entered_session": bool(entered),
            "timeout": timeout,
        }

    result: Dict[str, Any]
    # attempted：本次请求**可能已经动过显存**（ensure_mode 一开始就会停 llm/tts）。
    # entered  ：确实拿到了编排锁并进入了 comfy 会话（仅用于调试/日志语义）。
    # busy_conflict：连编排锁都没拿到 → 从未动过显存 → 不能调度恢复，也不能取消别人的恢复。
    attempted = False
    entered = False
    busy_conflict = False
    # 外层 try 只负责给 ApiError 补 resource；内层 try 负责异常映射与 finally 调度。
    # 顺序很关键：内层 finally（schedule 恢复）先跑，外层 except 才读得到 restore_scheduled。
    try:
        try:
            if not scheduler_enabled:
                # 关闭自动编排：直接用当前运行的 ComfyUI，不做任何模式切换
                _progress("generating")
                result = run_generate(ctx, params)
            else:
                # ensure_mode 会先停 llm/tts 再拉 ComfyUI：从这里开始就可能已经
                # 把显存腾空了，所以恢复判定必须早于 comfy_session（S2）。
                attempted = True
                # 编排阶段可能长达数分钟（停 LLM/TTS、等显存、拉 ComfyUI），
                # 异步任务在拿到编排锁之前就该让客户端看到"在排队资源"。
                _progress("orchestrating")
                # restore=None：恢复由本服务在回包之后自行调度，不能让响应等模型重载
                with manager.comfy_session(restore=None, lock_timeout=timeout + 600) as _info:
                    entered = True
                    if restore is not None:
                        # 拿到编排锁之后才置位：409 路径根本不该影响别人排好的恢复（S3）。
                        # comfy_wanted 的语义是"又来出图请求了，正在 grace 等待的恢复请让位"。
                        restore.comfy_wanted()
                    # 锁已拿到、ComfyUI 已在跑：接下来才是真正提交生成
                    _progress("generating")
                    result = run_generate(ctx, params)
        except ServiceBusy as exc:
            busy_conflict = True
            raise ApiError(409, "服务正忙（正在切换资源模式），请稍后重试", exc.detail) from exc
        except ServiceError as exc:
            # detail 里带 steps/status/log_tail，原样透出，方便前端直接看到卡在哪一步
            raise ApiError(502, f"资源编排失败：{exc}", exc.detail) from exc
        finally:
            # 只要本次请求可能动过显存（含"停完 llm/tts 后 ComfyUI 起不来"），
            # 就必须把恢复排上，否则机器会停在"LLM/TTS 已停、ComfyUI 未起"的空档。
            if restore is not None and attempted and not busy_conflict:
                # 只调度线程，不等待：生成成功/失败都要把显存还给 LLM
                restore_scheduled = restore.schedule()
    except ApiError as exc:
        exc.resource = _resource()
        raise

    _progress("finalizing")
    base = public_base or _public_base(None, ctx)
    images_out = _normalize_images(result, public_base=base, return_base64=return_base64)
    resource = _resource()
    # meta 可能为 None（老引擎/异常路径），兜底成 dict 才能塞进照片生效信息
    raw_meta = result.get("meta")
    meta: Dict[str, Any] = dict(raw_meta) if isinstance(raw_meta, dict) else {}
    meta["full_body_photo"] = params.get("full_body_photo")
    return {
        "ok": True,
        "prompt_id": result.get("prompt_id"),
        "elapsed_sec": result.get("elapsed_sec"),
        "elapsed_wall_sec": round(time.time() - started, 2),
        "images": images_out,
        "meta": meta,
        # 本次实际送给 workflow 的参考图张数（照片已并入，含被截断后的真实值）
        "reference_images": len(params.get("images") or []),
        "output_dir": os.path.abspath(str(ctx.get("output_dir") or "./outputs")),
        "resource": resource,
    }


# --------------------------------------------------------------------------- #
# WebUI（纯字符串常量 + __BOOT__ 注入；绝不使用 str.format()，否则 JS 的 {} 会被吃掉）
# --------------------------------------------------------------------------- #
HTML_APP = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ComfyUI 图像服务 · 显存编排台</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0 auto; padding: 18px 16px 48px; max-width: 980px; line-height: 1.6; color: #222;
         font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; background: #f6f7fb; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  h2 { font-size: 15px; margin: 0 0 12px; color: #333; }
  .sub { color: #6b7280; font-size: 13px; font-weight: 400; }
  .card { background: #fff; border: 1px solid #e6e8ef; border-radius: 12px; padding: 16px;
          margin-bottom: 14px; box-shadow: 0 1px 2px rgba(20, 30, 60, .04); }
  .row { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; justify-content: space-between; }
  .badges { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px;
           font-weight: 600; border: 1px solid transparent; background: #eef1f7; color: #4b5563; }
  .badge.idle { background: #f1f2f6; color: #555; }
  .badge.llm { background: #e8f0ff; color: #2b5bd7; border-color: #c9d9ff; }
  .badge.comfy { background: #fff0e5; color: #b45309; border-color: #ffd8b8; }
  .badge.mixed { background: #ffe9ec; color: #b42318; border-color: #ffc9d2; }
  .badge.dry { background: #fff4d6; color: #a15c00; border-color: #ffdf8a; }
  .badge.busy { background: #fdeaff; color: #8b2bbf; border-color: #ecc7ff; }
  .vram-wrap { margin-top: 10px; }
  .vram-bar { height: 10px; border-radius: 999px; background: #eceef4; overflow: hidden; }
  .vram-bar > i { display: block; height: 100%; width: 0;
                  background: linear-gradient(90deg, #4a7cff, #8b5cf6); transition: width .3s; }
  .vram-text { font-size: 12px; color: #6b7280; margin-top: 4px; }
  .svc-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
  .svc { border: 1px solid #e6e8ef; border-radius: 10px; padding: 12px; background: #fcfcfe; }
  .svc h3 { font-size: 14px; margin: 0 0 6px; }
  .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px;
         background: #b9bdc7; vertical-align: middle; }
  .dot.on { background: #16a34a; box-shadow: 0 0 0 3px rgba(22, 163, 74, .15); }
  .dot.off { background: #c02626; }
  .svc-meta { font-size: 12px; color: #6b7280; word-break: break-all; }
  .btn-row { display: flex; gap: 6px; margin-top: 10px; flex-wrap: wrap; align-items: center; }
  button { font: inherit; font-size: 13px; padding: 6px 12px; border-radius: 8px;
           border: 1px solid #d4d9e6; background: #fff; color: #24304d; cursor: pointer; }
  button:hover:not(:disabled) { border-color: #4a7cff; color: #4a7cff; }
  button.primary { background: #4a7cff; border-color: #4a7cff; color: #fff; }
  button.primary:hover:not(:disabled) { background: #3a6ae8; color: #fff; }
  button:disabled { opacity: .55; cursor: not-allowed; }
  label { display: block; font-size: 13px; color: #374151; margin-bottom: 8px; }
  input[type=text], input[type=number], textarea, select {
    width: 100%; font: inherit; font-size: 13px; padding: 7px 9px; margin-top: 4px;
    border: 1px solid #d4d9e6; border-radius: 8px; background: #fff; color: #1f2937; }
  textarea { resize: vertical; min-height: 68px; }
  .grid2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 0 12px; }
  .hint { font-size: 12px; color: #a15c00; background: #fff8e6; border: 1px solid #ffe6a8;
          border-radius: 8px; padding: 6px 10px; margin-bottom: 10px; }
  .muted { color: #6b7280; font-size: 12px; }
  pre { background: #f5f6fa; border: 1px solid #e6e8ef; border-radius: 8px; padding: 10px;
        font-size: 12px; overflow: auto; max-height: 320px; white-space: pre-wrap; word-break: break-all; }
  .res { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
  .res-card { border: 1px solid #e6e8ef; border-radius: 10px; padding: 10px; background: #fcfcfe; }
  .res-card img { max-width: 100%; border-radius: 8px; display: block; margin-bottom: 8px; background: #f0f2f7; }
  .res-card .fn { font-size: 12px; font-weight: 600; word-break: break-all; }
  .res-card a { font-size: 12px; color: #4a7cff; text-decoration: none; margin-right: 10px; }
  .overlay { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex;
             align-items: center; justify-content: center; padding: 16px; z-index: 50; }
  .overlay[hidden] { display: none; }
  .modal { background: #fff; border-radius: 12px; width: min(860px, 100%); max-height: 84vh;
           display: flex; flex-direction: column; }
  .modal-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px;
                border-bottom: 1px solid #e6e8ef; font-size: 14px; gap: 12px; }
  .modal pre { margin: 0; border: 0; border-radius: 0 0 12px 12px; max-height: 70vh; }
  .status-line { font-size: 13px; color: #4b5563; margin: 10px 0 4px; word-break: break-all; }
  @media (max-width: 560px) { body { padding: 12px 10px 40px; } h1 { font-size: 17px; } }
</style>
</head>
<body>
<header class="card">
  <div class="row">
    <div>
      <h1>ComfyUI 图像服务 <span class="sub">· 显存编排台</span></h1>
      <div class="muted" id="boot-line"></div>
    </div>
    <div class="badges">
      <span class="badge" id="mode-badge">模式 —</span>
      <span class="badge busy" id="busy-badge" hidden>生成中</span>
      <span class="badge dry" id="dry-badge" hidden>DRY-RUN 模拟模式</span>
    </div>
  </div>
  <div class="vram-wrap">
    <div class="vram-bar"><i id="vram-fill"></i></div>
    <div class="vram-text" id="vram-text">显存 —</div>
  </div>
  <div class="row" style="margin-top:10px">
    <label class="muted" style="margin:0;display:flex;align-items:center;gap:8px">
      <span>访问令牌</span>
      <input type="text" id="f-token" placeholder="未启用 --token 可留空" style="width:220px;margin:0">
    </label>
    <span class="muted" id="auth-hint"></span>
  </div>
</header>

<main>
  <section class="card">
    <h2>受管服务</h2>
    <div class="svc-grid" id="svc-grid"></div>
    <div class="btn-row" style="margin-top:12px">
      <button id="btn-mode-llm" class="primary">切到 LLM 模式</button>
      <button id="btn-mode-comfy" class="primary">切到 ComfyUI 模式</button>
      <button id="btn-refresh">刷新状态</button>
    </div>
    <div class="status-line" id="sched-line">正在读取状态…</div>
    <div class="hint" id="sched-off" hidden>自动编排已关闭，生成将直接使用当前 ComfyUI（--no-scheduler）。</div>
    <pre id="action-log" hidden></pre>
  </section>

  <section class="card">
    <h2>生成</h2>
    <label>提示词 prompt<textarea id="f-prompt" placeholder="例如：把背景换成雪山夜景，保持人物姿态不变"></textarea></label>
    <label>负向提示词 negative<input type="text" id="f-negative" placeholder="可留空"></label>
    <div class="grid2">
      <label>尺寸 size
        <select id="f-size">
          <option value="768x768">768x768</option>
          <option value="1024x1024" selected>1024x1024</option>
          <option value="1080x1920">1080x1920</option>
          <option value="720x1280">720x1280</option>
          <option value="custom">自定义…</option>
        </select>
      </label>
      <label>自定义尺寸<input type="text" id="f-size-custom" placeholder="WxH，如 832x1216" disabled></label>
      <label>步数 steps<input type="number" id="f-steps" value="4" min="1" max="200"></label>
      <label>随机种子 seed<input type="number" id="f-seed" placeholder="留空 = 随机"></label>
      <label>张数 batch_size<input type="number" id="f-batch" value="1" min="1" max="8"></label>
    </div>
    <label>参考图（逗号分隔，最多 2 张；可填本地路径或 ComfyUI input/ 内文件名）
      <input type="text" id="f-images" placeholder="/root/pic/a.png, ComfyUI_00009_.png">
    </label>
    <label style="display:flex;align-items:center;gap:8px;margin-bottom:12px">
      <input type="checkbox" id="f-noref" style="width:auto;margin:0">
      <span>不使用参考图 no_reference（纯文生图）</span>
    </label>
    <div class="btn-row">
      <button id="btn-generate" class="primary">生成</button>
      <button id="btn-clear">清空结果</button>
    </div>
    <div class="status-line" id="gen-status">就绪</div>
  </section>

  <section class="card">
    <h2>结果</h2>
    <div class="res" id="results"><div class="muted">还没有生成结果。</div></div>
    <pre id="gen-detail" hidden></pre>
  </section>
</main>

<div class="overlay" id="overlay" hidden>
  <div class="modal">
    <div class="modal-head">
      <b id="modal-title">日志</b>
      <button id="modal-close">关闭</button>
    </div>
    <pre id="modal-body"></pre>
  </div>
</div>

<script>
const BOOT = __BOOT__;
const $ = (id) => document.getElementById(id);
const state = { data: null, generating: false, timer: null, visible: !document.hidden };

// ---- 可选鉴权（--token）：令牌只存在本机 localStorage，随请求带 X-Auth-Token 头 ----
const TOKEN_KEY = "comfy_api_token";
let authToken = "";
try { authToken = localStorage.getItem(TOKEN_KEY) || ""; } catch (e) { authToken = ""; }

function withToken(url) {
  // 图片 <img src> / <a href> 没法加自定义头，只能把令牌拼进查询参数
  if (!authToken || !url) return url;
  return url + (url.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(authToken);
}

function saveToken(value) {
  authToken = String(value || "").trim();
  try {
    if (authToken) localStorage.setItem(TOKEN_KEY, authToken);
    else localStorage.removeItem(TOKEN_KEY);
  } catch (e) { /* 无痕模式可能禁用 localStorage，忽略 */ }
}

function fmtSize(n) {
  if (n === null || n === undefined || isNaN(n)) return "—";
  const v = Number(n);
  if (v < 1024) return v + " B";
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
  return (v / 1024 / 1024).toFixed(2) + " MB";
}

function numOrNull(value) {
  const text = String(value === undefined || value === null ? "" : value).trim();
  if (!text) return null;
  const num = Number(text);
  return isNaN(num) ? null : num;
}

async function api(path, options) {
  const opts = Object.assign({}, options || {});
  const headers = Object.assign({}, opts.headers || {});
  if (authToken) headers["X-Auth-Token"] = authToken;   // 见 --token
  opts.headers = headers;
  const resp = await fetch(path, opts);
  let data = null;
  try { data = await resp.json(); } catch (e) { data = null; }
  if (data === null) data = { ok: false, error: "HTTP " + resp.status + "（响应不是 JSON）" };
  if (!resp.ok || data.ok === false) {
    const msg = resp.status === 401
      ? '未授权：请先在顶部「访问令牌」里填入 --token 的值'
      : (data.error || ("HTTP " + resp.status));
    const err = new Error(msg);
    err.payload = data;
    err.status = resp.status;
    throw err;
  }
  return data;
}

function showJson(el, value) {
  el.hidden = false;
  el.textContent = JSON.stringify(value, null, 2);
}

function showError(err, target) {
  const el = target || $("action-log");
  showJson(el, err && err.payload ? err.payload : { error: String(err) });
}

function setBadge(mode) {
  const names = { idle: "空闲", llm: "LLM 模式", comfy: "ComfyUI 模式", mixed: "混合（显存风险）", unknown: "未知" };
  const el = $("mode-badge");
  el.textContent = "模式：" + (names[mode] || mode || "—");
  el.className = "badge " + (["idle", "llm", "comfy", "mixed"].indexOf(mode) >= 0 ? mode : "");
}

function renderVram(vram) {
  const fill = $("vram-fill");
  const text = $("vram-text");
  if (!vram || !vram.total_mib) {
    fill.style.width = "0%";
    text.textContent = "显存 —（nvidia-smi 不可用）";
    return;
  }
  const pct = Math.max(0, Math.min(100, Math.round(vram.used_mib / vram.total_mib * 100)));
  fill.style.width = pct + "%";
  text.textContent = "显存 " + vram.used_mib + " / " + vram.total_mib + " MiB（" + pct +
    "%，空闲 " + vram.free_mib + " MiB）";
}

function mkButton(label, onClick) {
  const btn = document.createElement("button");
  btn.textContent = label;
  btn.addEventListener("click", () => onClick(btn));
  return btn;
}

function renderServices(list) {
  const grid = $("svc-grid");
  grid.innerHTML = "";
  if (!list || !list.length) {
    const empty = document.createElement("div");
    empty.className = "muted";
    empty.textContent = "没有受管服务（未启用资源编排）。";
    grid.appendChild(empty);
    return;
  }
  list.forEach((svc) => {
    const box = document.createElement("div");
    box.className = "svc";
    const title = document.createElement("h3");
    const dot = document.createElement("span");
    dot.className = "dot " + (svc.running ? "on" : "off");
    title.appendChild(dot);
    title.appendChild(document.createTextNode(svc.name + " · " + (svc.label || "")));
    box.appendChild(title);

    const meta = document.createElement("div");
    meta.className = "svc-meta";
    const pids = svc.pids && svc.pids.length ? svc.pids.join(",") : "—";
    meta.textContent = "端口 " + svc.port + " · " + (svc.running ? "运行中" : "已停止") +
      " · pid " + pids + (svc.vram_hint ? " · 显存 " + svc.vram_hint : "");
    box.appendChild(meta);
    if (svc.last_error) {
      const err = document.createElement("div");
      err.className = "svc-meta";
      err.style.color = "#b42318";
      err.textContent = "上次错误：" + svc.last_error;
      box.appendChild(err);
    }

    const row = document.createElement("div");
    row.className = "btn-row";
    row.appendChild(mkButton("启动", (btn) => act(svc.name, "start", btn)));
    row.appendChild(mkButton("停止", (btn) => act(svc.name, "stop", btn)));
    row.appendChild(mkButton("日志", () => showLog(svc.name)));
    box.appendChild(row);
    grid.appendChild(box);
  });
}

async function withBtn(btn, fn) {
  const old = btn.textContent;
  btn.disabled = true;
  btn.textContent = "处理中…";
  try {
    await fn();
  } finally {
    btn.disabled = false;
    btn.textContent = old;
  }
}

async function act(name, action, btn) {
  await withBtn(btn, async () => {
    $("gen-status").textContent = name + " " + action + " 处理中…";
    try {
      const data = await api("/api/services/" + encodeURIComponent(name) + "/" + action, { method: "POST" });
      $("action-log").hidden = true;
      const result = data.result || {};
      $("gen-status").textContent = name + " " + action + " 完成" +
        (result.already_running ? "（已在运行）" : "") + (result.simulated ? "（dry-run 模拟）" : "");
    } catch (err) {
      $("gen-status").textContent = name + " " + action + " 失败：" + (err.message || err);
      showError(err);
    }
    await refresh(true);
  });
}

async function switchMode(mode, btn) {
  await withBtn(btn, async () => {
    $("gen-status").textContent = "正在切到 " + mode + " 模式（可能要停服务/等显存，请耐心等）…";
    $("action-log").hidden = true;
    try {
      const data = await api("/api/mode/" + mode, { method: "POST" });
      $("gen-status").textContent = "已切到 " + (data.mode || mode) + " 模式（switched=" +
        data.switched + "，步骤 " + ((data.steps || []).length) + "）";
    } catch (err) {
      $("gen-status").textContent = "切换失败：" + (err.message || err);
      showError(err);
    }
    await refresh(true);
  });
}

async function showLog(name) {
  try {
    const data = await api("/api/services/" + encodeURIComponent(name) + "/log?lines=80");
    $("modal-title").textContent = name + " 日志（末尾 " + data.lines + " 行）";
    $("modal-body").textContent = data.log && data.log.length ? data.log : "（日志为空）";
    $("overlay").hidden = false;
  } catch (err) {
    showError(err);
  }
}

function stopTimer() {
  if (state.timer) { clearTimeout(state.timer); state.timer = null; }
}

function startTimer() {
  stopTimer();
  if (state.visible) state.timer = setTimeout(tick, 3000);
}

async function refresh(force) {
  if (state.generating && !force) return;   // 生成期间暂停状态轮询
  try {
    const data = await api("/api/services");
    state.data = data;
    setBadge(data.mode);
    renderVram(data.vram);
    renderServices(data.services);
    $("dry-badge").hidden = !data.dry_run;
    $("busy-badge").hidden = !data.busy;
    const target = data.restore_target || "none";
    const delay = data.restore_delay === null || data.restore_delay === undefined ? "-" : data.restore_delay;
    $("sched-line").textContent = "自动编排：" + (data.scheduler_enabled ? "开启" : "关闭") +
      " · 恢复目标：" + target + "（" + (data.restore_mode || "-") + "，" + delay + "s）" +
      " · 引擎：" + (data.generate_engine || "-") +
      (data.pending_restore ? " · 有待执行的恢复" : "");
    $("sched-off").hidden = !!data.scheduler_enabled;
  } catch (err) {
    $("sched-line").textContent = "状态获取失败：" + (err.message || err);
  }
}

async function tick() {
  state.timer = null;
  if (!state.visible || state.generating) return;
  await refresh();
  startTimer();
}

function renderResult(data) {
  const box = $("results");
  box.innerHTML = "";
  const images = data.images || [];
  if (!images.length) {
    const empty = document.createElement("div");
    empty.className = "muted";
    empty.textContent = "响应里没有图片。";
    box.appendChild(empty);
  }
  images.forEach((img) => {
    const card = document.createElement("div");
    card.className = "res-card";
    if (img.download_url) {
      const im = document.createElement("img");
      im.src = withToken(img.download_url);
      im.alt = img.filename || "result";
      im.loading = "lazy";
      card.appendChild(im);
    }
    const fn = document.createElement("div");
    fn.className = "fn";
    fn.textContent = img.filename || "(未命名)";
    card.appendChild(fn);
    const meta = document.createElement("div");
    meta.className = "muted";
    meta.textContent = fmtSize(img.size_bytes) + " · " + (img.saved_path || "") +
      (img.comfy_filename && img.comfy_filename !== img.filename
        ? " · ComfyUI 原名 " + img.comfy_filename : "");
    card.appendChild(meta);
    const links = document.createElement("div");
    if (img.download_url) {
      const a1 = document.createElement("a");
      a1.href = withToken(img.download_url);
      a1.textContent = "下载";
      a1.target = "_blank";
      links.appendChild(a1);
    }
    if (img.download_url_attachment) {
      const a2 = document.createElement("a");
      a2.href = withToken(img.download_url_attachment);
      a2.textContent = "另存";
      links.appendChild(a2);
    }
    card.appendChild(links);
    box.appendChild(card);
  });
  const res = data.resource || {};
  $("gen-status").textContent = "完成：" + images.length + " 张 · 耗时 " +
    (data.elapsed_wall_sec === undefined ? "?" : data.elapsed_wall_sec) + "s · prompt_id=" +
    (data.prompt_id || "-") + " · 模式=" + (res.mode || "?") + " · 后台恢复=" +
    (res.restore_scheduled ? "已调度" : "未调度");
  showJson($("gen-detail"), { meta: data.meta, resource: data.resource, output_dir: data.output_dir });
}

async function generate() {
  const prompt = $("f-prompt").value.trim();
  if (!prompt) {
    $("gen-status").textContent = "请先填写提示词 prompt";
    return;
  }
  let size = $("f-size").value;
  if (size === "custom") size = $("f-size-custom").value.trim();
  const refs = $("f-images").value.split(",").map((s) => s.trim()).filter(Boolean);
  if (refs.length > 2) {
    $("gen-status").textContent = "参考图最多 2 张，当前 " + refs.length + " 张";
    return;
  }
  const body = { prompt: prompt };
  const negative = $("f-negative").value.trim();
  if (negative) body.negative = negative;
  if (size) body.size = size;
  const steps = numOrNull($("f-steps").value);
  if (steps !== null) body.steps = steps;
  const seed = numOrNull($("f-seed").value);
  if (seed !== null) body.seed = seed;
  const batch = numOrNull($("f-batch").value);
  if (batch !== null) body.batch_size = batch;
  if (refs.length) body.images = refs;
  if ($("f-noref").checked) body.no_reference = true;

  state.generating = true;
  stopTimer();                       // 生成期间暂停状态轮询，避免抢带宽/刷屏
  const btn = $("btn-generate");
  btn.disabled = true;
  btn.textContent = "生成中…";
  $("busy-badge").hidden = false;
  $("gen-detail").hidden = true;
  $("gen-status").textContent = "生成中…（首次会先加载 ComfyUI，可能需要数分钟）";
  try {
    const data = await api("/api/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    renderResult(data);
  } catch (err) {
    $("gen-status").textContent = "生成失败：" + (err.message || err);
    showError(err, $("gen-detail"));
  } finally {
    state.generating = false;
    btn.disabled = false;
    btn.textContent = "生成";
    startTimer();
    refresh(true);
  }
}

document.addEventListener("DOMContentLoaded", () => {
  $("boot-line").textContent = "ComfyUI " + BOOT.base_url + " · 服务端口 " + BOOT.port +
    " · 引擎 " + BOOT.engine + (BOOT.public_base ? " · 下载基址 " + BOOT.public_base : "");
  $("dry-badge").hidden = !BOOT.dry_run;
  // 可选鉴权：--token 未启用时这一栏留着不影响任何请求
  $("auth-hint").textContent = BOOT.auth_required
    ? (authToken ? "已保存令牌" : "服务启用了 --token，请填写令牌")
    : "服务未启用 --token，可留空";
  const tokenInput = $("f-token");
  tokenInput.value = authToken;
  tokenInput.addEventListener("change", () => {
    saveToken(tokenInput.value);
    $("auth-hint").textContent = authToken ? "令牌已保存（每次请求都会带上）" : "已清空令牌";
    refresh(true);
  });
  $("f-size").addEventListener("change", () => {
    const custom = $("f-size").value === "custom";
    $("f-size-custom").disabled = !custom;
    if (custom) $("f-size-custom").focus();
  });
  $("btn-generate").addEventListener("click", generate);
  $("btn-refresh").addEventListener("click", () => refresh(true));
  $("btn-mode-llm").addEventListener("click", (ev) => switchMode("llm", ev.currentTarget));
  $("btn-mode-comfy").addEventListener("click", (ev) => switchMode("comfy", ev.currentTarget));
  $("btn-clear").addEventListener("click", () => {
    $("results").innerHTML = '<div class="muted">还没有生成结果。</div>';
    $("gen-detail").hidden = true;
    $("gen-status").textContent = "就绪";
  });
  $("modal-close").addEventListener("click", () => { $("overlay").hidden = true; });
  $("overlay").addEventListener("click", (ev) => {
    if (ev.target === $("overlay")) $("overlay").hidden = true;
  });
  document.addEventListener("visibilitychange", () => {
    state.visible = !document.hidden;
    if (state.visible) { tick(); } else { stopTimer(); }
  });
  refresh(true).then(startTimer);
});
</script>
</body>
</html>
"""


def build_boot(ctx: Dict[str, Any], host_header: Optional[str] = None) -> Dict[str, Any]:
    """WebUI 启动配置（注入到 __BOOT__，只放前端真正用得到的字段）。"""
    config: Dict[str, Any] = ctx.get("config") or {}
    manager: Optional[ServiceManager] = ctx.get("manager")
    return {
        "host": ctx.get("host"),
        "port": ctx.get("port"),
        "base_url": str(ctx.get("client").base_url if ctx.get("client") else DEFAULT_BASE_URL),
        "public_base": _public_base(host_header, ctx),
        "scheduler_enabled": bool(ctx.get("scheduler_enabled")),
        "dry_run": bool(manager.dry_run) if manager is not None else False,
        "restore_target": config.get("restore_target"),
        "restore_mode": config.get("restore_mode"),
        "restore_delay": config.get("restore_delay"),
        "comfy_port": config.get("comfy_port"),
        "llm_port": config.get("llm_port"),
        "tts_port": config.get("tts_port"),
        "generate_timeout": config.get("generate_timeout"),
        "engine": config.get("generate_engine"),
        # 前端据此决定是否提示输入访问令牌（--token 未设置时为 False，行为不变）
        "auth_required": bool(ctx.get("token")),
    }


def _boot_json(boot: Dict[str, Any]) -> str:
    """把 boot 配置序列化成可安全嵌进 ``<script>`` 的 JSON。

    ``json.dumps`` 不转义 ``<`` / ``>`` / ``&``，用户可控字段里出现 ``</script>``
    会直接闭合脚本块（XSS）。这里统一转成 ``\\u003c`` 这类 JSON 转义——JSON.parse
    之后仍然是原字符，但 HTML 解析器看不到闭合标签。
    """
    text = json.dumps(boot, ensure_ascii=False)
    return (
        text.replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def render_index(ctx: Dict[str, Any], host_header: Optional[str] = None) -> str:
    """渲染首页：用 replace 注入 JSON（已做 </script> 转义），绝不用 str.format()。"""
    return HTML_APP.replace("__BOOT__", _boot_json(build_boot(ctx, host_header)))


# --------------------------------------------------------------------------- #
# HTTP Handler
# --------------------------------------------------------------------------- #
def _require_service(manager: Optional[ServiceManager], name: str) -> str:
    """校验服务名：不认识就 404（而不是让它冒成 502）。"""
    if manager is None:
        raise ApiError(400, "未启用资源编排（manager=None），无法操作服务")
    key = str(name or "").strip().lower()
    try:
        manager.service_status(key)
    except ServiceError as exc:
        raise ApiError(404, f"未知服务：{name}", exc.detail) from exc
    except ServiceBusy as exc:
        raise ApiError(409, "服务正忙（正在切换资源模式），请稍后重试", exc.detail) from exc
    except Exception as exc:  # noqa: BLE001 - 兜底成 404，绝不 500
        raise ApiError(404, f"未知服务：{name}", {"error": str(exc)}) from exc
    return key


class ComfyApiHandler(BaseHTTPRequestHandler):
    server_version = SERVER_NAME
    protocol_version = "HTTP/1.1"

    # 由 make_server() 注入
    app: Dict[str, Any] = {}
    # 每个请求在 _dispatch 里复位：是否强制关连接 / 请求体是否已被消费
    _force_close = False
    _body_read = False

    # -------------------------- 工具方法 -------------------------- #
    def _cors_origin(self) -> str:
        """回显的 CORS 源：--cors-origin 配置优先，默认 ``*``（与旧行为一致）。"""
        return str((self.app or {}).get("cors_origin") or "*")

    def _cors_headers(self) -> List[Tuple[str, str]]:
        return [
            ("Access-Control-Allow-Origin", self._cors_origin()),
            ("Access-Control-Allow-Headers", "Content-Type, X-Auth-Token"),
            # DELETE 是照片库用的（DELETE /api/photos/<id>）
            ("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"),
        ]

    def _close_after_response(self) -> None:
        """本次响应后关闭连接。

        请求体没被消费时必须这么做：``protocol_version = "HTTP/1.1"`` 默认 keep-alive，
        残留的 body 会被当成下一个请求行解析，让同一连接上的后续请求全部错乱。
        """
        self.close_connection = True
        self._force_close = True

    def _send_json(self, obj: Any, status: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for key, value in self._cors_headers():
            self.send_header(key, value)
        if self._force_close:
            self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):  # pragma: no cover
            pass

    def _send_html(self, html: str, status: int = 200) -> None:
        body = html.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", self._cors_origin())
        if self._force_close:
            self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):  # pragma: no cover
            pass

    def _send_bytes(
        self,
        body: bytes,
        content_type: str,
        extra_headers: Optional[List[Tuple[str, str]]] = None,
        status: int = 200,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", self._cors_origin())
        for key, value in extra_headers or []:
            self.send_header(key, value)
        if self._force_close:
            self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):  # pragma: no cover
            pass

    def _send_file_stream(
        self,
        target: str,
        content_type: str,
        size: int,
        extra_headers: Optional[List[Tuple[str, str]]] = None,
        chunk_size: int = 64 * 1024,
    ) -> None:
        """以固定 Content-Length + 64KB 分块把文件写出去（不把整图读进内存）。"""
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        self.send_header("Access-Control-Allow-Origin", self._cors_origin())
        for key, value in extra_headers or []:
            self.send_header(key, value)
        if self._force_close:
            self.send_header("Connection", "close")
        self.end_headers()
        sent = 0
        try:
            with open(target, "rb") as fh:
                while True:
                    chunk = fh.read(chunk_size)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    sent += len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            # 客户端提前断开：正常现象，吞掉即可（响应头已经发出，没法再改状态码）
            self.close_connection = True
            return
        except OSError as exc:
            sys.stderr.write("[warn] 读文件 %s 失败：%s\n" % (target, exc))
            self.close_connection = True
            return
        if sent != size:
            # 文件在发送过程中被截断：长度头已经不对了，只能断开连接避免错位
            self.close_connection = True

    def _safe_json(self, obj: Any, status: int = 200) -> None:
        """错误兜底发送：响应已经写了一半时不能因为二次写而炸掉进程。"""
        try:
            self._send_json(obj, status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:  # pragma: no cover
            traceback.print_exc()

    def _read_json_body(self) -> Dict[str, Any]:
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            raise ApiError(411, "缺少 Content-Length")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise ApiError(400, "Content-Length 非法") from exc
        if length > MAX_BODY_BYTES:
            self._close_after_response()   # body 不读，必须断连，否则 keep-alive 错位
            raise ApiError(413, f"请求体过大（{length} 字节），上限 {MAX_BODY_BYTES} 字节")
        if length <= 0:
            self._body_read = True         # 没有 body 需要消费，连接可以继续复用
            raise ApiError(400, "请求体为空")
        raw = self.rfile.read(length)
        self._body_read = True             # 已按声明长度读完，keep-alive 可以复用
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ApiError(400, "请求体不是合法 UTF-8") from exc
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ApiError(400, f"请求体不是合法 JSON：{exc}") from exc
        if not isinstance(data, dict):
            raise ApiError(400, "请求体必须是 JSON 对象")
        return data

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        sys.stderr.write(
            "[%s] %s %s\n" % (time.strftime("%H:%M:%S"), self.address_string(), fmt % args)
        )

    # -------------------------- 路由 -------------------------- #
    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(HTTPStatus.NO_CONTENT)
        for key, value in self._cors_headers():
            self.send_header(key, value)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch("DELETE")

    def _check_auth(self, path: str, query: Dict[str, List[str]]) -> None:
        """可选鉴权：``--token`` 未设置时零开销直接放行（默认行为完全不变）。

        命中要求：``X-Auth-Token`` 头，或 ``?token=`` 查询参数（供 ``<img>``/下载链接用）。
        """
        token = str((self.app or {}).get("token") or "")
        if not token or not _is_protected_path(path):
            return
        provided = str(self.headers.get("X-Auth-Token") or "").strip()
        if not provided:
            provided = str((query.get("token") or [""])[0]).strip()
        if not provided or not hmac.compare_digest(provided, token):
            raise ApiError(
                401,
                "缺少或错误的访问令牌：请带上 X-Auth-Token 头，或在 URL 上加 ?token=",
                {"auth_required": True},
            )

    def _maybe_close_unread_body(self, method: str) -> None:
        """带 body 的方法（POST/DELETE）没读 body 就断连，否则 keep-alive 上后续请求会错位。"""
        if method != "GET" and not self._body_read:
            self._close_after_response()

    def _dispatch(self, method: str) -> None:
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query)
        started = time.time()
        self._force_close = False
        self._body_read = False
        try:
            try:
                # 鉴权先于路由：未授权时连 404 都不要泄露路由是否存在
                self._check_auth(path, query)
                if method == "GET":
                    self._route_get(path, query)
                elif method == "DELETE":
                    self._route_delete(path, query)
                else:
                    self._route_post(path, query)
            except BaseException:
                # 任何提前返回/异常：POST 的 body 还没读就统一关连接，绝不让它留到下一个请求
                self._maybe_close_unread_body(method)
                raise
        except ApiError as exc:
            payload: Dict[str, Any] = {"ok": False, "error": exc.message, "detail": exc.detail}
            if exc.resource:  # 生成失败时也把"资源模式/恢复是否排上"透出
                payload["resource"] = exc.resource
            self._safe_json(payload, exc.status)
        except ServiceBusy as exc:
            self._safe_json(
                {"ok": False, "error": "服务正忙（正在切换资源模式），请稍后重试", "detail": exc.detail},
                409,
            )
        except ServiceError as exc:
            # detail 里的 steps / status / log_tail 原样透出，别只回一句"失败"
            self._safe_json({"ok": False, "error": f"资源编排失败：{exc}", "detail": exc.detail}, 502)
        except ComfyError as exc:
            self._safe_json({"ok": False, "error": str(exc), "detail": exc.detail}, 502)
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception as exc:  # noqa: BLE001 - 兜底，绝不让未捕获异常打断服务
            traceback.print_exc()
            self._safe_json({"ok": False, "error": f"服务器内部错误：{exc}"}, 500)
        finally:
            sys.stderr.write(
                f"[{time.strftime('%H:%M:%S')}] {method} {path} 耗时 {time.time() - started:.3f}s\n"
            )

    def _route_get(self, path: str, query: Dict[str, List[str]]) -> None:
        app = self.app or {}
        if path in ("/", "/index.html"):
            self._send_html(render_index(app, self.headers.get("Host")))
            return
        if path == "/api/health":
            payload = health_payload(app["client"], app)
            self._send_json(payload, 200 if payload.get("ok") else 503)
            return
        if path == "/api/services":
            self._send_json(services_payload(app))
            return
        if path == "/api/workflow":
            self._send_json(
                {
                    "ok": True,
                    "template": app["template"],
                    "template_path": app["template_path"],
                }
            )
            return
        if path == "/api/queue":
            self._send_json({"ok": True, "queue": app["client"].queue()})
            return
        if path == "/api/jobs":
            self._list_jobs(query)
            return
        if path.startswith("/api/jobs/"):
            self._get_job(path[len("/api/jobs/"):])
            return
        # 照片库：列表 / 单张图片流（顺序固定，"列表"必须先于"按 id 取"）
        if path == "/api/photos":
            self._list_photos()
            return
        if path.startswith("/api/photos/"):
            self._serve_photo(path[len("/api/photos/"):], query)
            return
        if path == "/files":
            raise ApiError(400, "必须在 /files/ 后面给出文件名")
        if path.startswith("/files/"):
            self._serve_file(path[len("/files/"):], query)
            return
        parts = path.strip("/").split("/")
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "services" and parts[3] == "log":
            manager: Optional[ServiceManager] = app.get("manager")
            name = _require_service(manager, parts[2])
            lines = 80
            raw_lines = (query.get("lines") or [None])[0]
            if raw_lines is not None:
                try:
                    lines = int(raw_lines)
                except (TypeError, ValueError) as exc:
                    raise ApiError(400, f"lines 必须是整数，收到：{raw_lines!r}") from exc
            lines = max(1, min(2000, lines))
            log = manager.log_tail(name, lines) if manager is not None else ""
            self._send_json({"ok": True, "name": name, "lines": lines, "log": log})
            return
        raise ApiError(404, f"未知路径：{path}")

    def _route_post(self, path: str, query: Dict[str, List[str]]) -> None:
        app = self.app or {}
        parts = path.strip("/").split("/")
        # 只有 /api/generate、/api/generate/async、/api/prompt 与 /api/photos 会读请求体；
        # 其余 POST 路径一律不读，所以统一在响应后关连接，避免残留 body 把 keep-alive 上的
        # 下一个请求吃掉（S9）。新增会读 body 的路径必须一起加进这个白名单。
        if path not in ("/api/generate", "/api/generate/async", "/api/prompt", "/api/photos"):
            self._close_after_response()
        if path == "/api/photos":
            self._upload_photo()
            return
        if path == "/api/generate/async":
            self._submit_async_generate()
            return
        if path == "/api/generate":
            payload = self._read_json_body()
            result = handle_generate(
                payload, app, public_base=_public_base(self.headers.get("Host"), app)
            )
            self._send_json(result, 200)
            return
        if path == "/api/prompt":
            # 只构建不执行，便于调试
            payload = self._read_json_body()
            prompt = payload.get("prompt")
            if not isinstance(prompt, str) or not prompt.strip():
                raise ApiError(400, "字段 prompt 必填")
            images = _resolve_images(payload)
            width = height = None
            if payload.get("size"):
                try:
                    width, height = parse_size(payload["size"])
                except ValueError as exc:
                    raise ApiError(400, str(exc)) from exc
            api, meta = build_prompt(
                app["template"], prompt,
                negative=payload.get("negative"), images=images,
                client=app["client"], no_reference=_as_bool(payload.get("no_reference")),
                seed=payload.get("seed"), steps=payload.get("steps"), cfg=payload.get("cfg"),
                sampler=payload.get("sampler"), scheduler=payload.get("scheduler"),
                denoise=payload.get("denoise"), width=width, height=height,
                batch_size=payload.get("batch_size"),
                filename_prefix=payload.get("filename_prefix"),
            )
            self._send_json({"ok": True, "prompt": api, "meta": meta}, 200)
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "services" and parts[3] in ("start", "stop"):
            manager: Optional[ServiceManager] = app.get("manager")
            name = _require_service(manager, parts[2])
            action = parts[3]
            assert manager is not None  # _require_service 已经拦住 None
            if action == "start":
                result = manager.start_service(name)
            else:
                result = manager.stop_service(name)
            self._send_json({"ok": True, "action": action, "name": name, "result": result}, 200)
            return
        if len(parts) == 3 and parts[0] == "api" and parts[1] == "mode":
            mode = parts[2].strip().lower()
            if mode not in VALID_MODES:
                raise ApiError(400, f"未知模式：{parts[2]}（只支持 llm / comfy）")
            manager = app.get("manager")
            if manager is None:
                raise ApiError(400, "未启用资源编排（manager=None），无法切换模式")
            restore: Optional[RestoreScheduler] = app.get("restore_scheduler")
            if restore is not None:
                restore.cancel_pending()   # 手动切模式优先，取消挂起的后台恢复
            result = manager.ensure_mode(mode)
            self._send_json(
                {
                    "ok": True,
                    "mode": result.get("mode", mode),
                    "switched": result.get("switched"),
                    "steps": result.get("steps") or [],
                    "previous_mode": result.get("previous_mode"),
                    "mode_after": result.get("mode_after"),
                },
                200,
            )
            return
        raise ApiError(404, f"未知路径：{path}")

    def _route_delete(self, path: str, query: Dict[str, List[str]]) -> None:
        """DELETE 路由：目前只有照片库用（DELETE /api/photos/<id>）。"""
        if path.startswith("/api/photos/"):
            self._delete_photo(path[len("/api/photos/"):])
            return
        raise ApiError(404, f"未知路径：{path}")

    # -------------------------- 异步任务（/api/generate/async、/api/jobs） -------------------------- #
    def _job_store(self) -> JobStore:
        store = (self.app or {}).get("job_store")
        if store is None:
            raise ApiError(400, "未启用异步任务（job_store=None）")
        return store

    def _request_public_base(self) -> str:
        """下载地址基址：必须取**当前这次请求**的 Host。

        任务可能在 Wi-Fi 下提交、4G 下查询，IP 早就变了；沿用提交时的地址会让
        客户端拿到一个连不上的域名。
        """
        return _public_base(self.headers.get("Host"), self.app or {})

    def _submit_async_generate(self) -> None:
        """POST /api/generate/async：立即返回任务快照（新建 202 / 幂等命中 200）。"""
        app = self.app or {}
        store = self._job_store()
        payload = self._read_json_body()   # 先把 body 消费掉，keep-alive 才不会错位
        base = self._request_public_base()
        record, created = submit_generate_job(payload, app, store)
        self._send_json({"ok": True, **_job_snapshot(record, public_base=base)}, 202 if created else 200)

    def _get_job(self, raw_key: str) -> None:
        """GET /api/jobs/<key>：key 先当 job_id，再当 client_job_id。"""
        store = self._job_store()
        key = unquote(str(raw_key or ""))
        record = store.resolve(key)
        if record is None:
            self._send_json({"ok": False, "error": "任务不存在：%s" % key}, 404)
            return
        self._send_json({"ok": True, **_job_snapshot(record, public_base=self._request_public_base())}, 200)

    def _list_jobs(self, query: Dict[str, List[str]]) -> None:
        """GET /api/jobs?limit=20&status=running,succeeded：按 created_at 倒序列最近任务。"""
        store = self._job_store()
        raw_limit = (query.get("limit") or [None])[0]
        limit = DEFAULT_JOB_LIMIT
        if raw_limit is not None and str(raw_limit).strip() != "":
            try:
                limit = int(str(raw_limit).strip())
            except (TypeError, ValueError) as exc:
                raise ApiError(400, f"limit 必须是整数，收到：{raw_limit!r}") from exc
            limit = max(1, min(MAX_JOB_LIMIT, limit))

        raw_status = (query.get("status") or [None])[0]
        statuses: Optional[List[str]] = None
        if raw_status is not None and str(raw_status).strip() != "":
            statuses = []
            for token in str(raw_status).split(","):
                value = token.strip()
                if not value:
                    continue
                if value not in JOB_STATUSES:
                    raise ApiError(
                        400,
                        "字段 status 含非法值：%r（只支持 %s）"
                        % (value, " / ".join(JOB_STATUSES)),
                    )
                if value not in statuses:
                    statuses.append(value)
            if not statuses:
                raise ApiError(400, "字段 status 不能为空")

        records = store.list_jobs(limit=limit, statuses=statuses)
        base = self._request_public_base()
        now = time.time()
        jobs = [
            _job_snapshot(record, public_base=base, now=now, compact=True)
            for record in records
        ]
        self._send_json({"ok": True, "count": len(jobs), "jobs": jobs}, 200)

    # -------------------------- 照片库（/api/photos） -------------------------- #
    def _photo_store(self) -> PhotoStore:
        store = (self.app or {}).get("photo_store")
        if store is None:
            raise ApiError(400, "未启用照片库（photo_store=None）")
        return store

    def _photo_payload(self, info: Dict[str, Any], base: Optional[str] = None) -> Dict[str, Any]:
        """照片元数据 → 对外 JSON：补上按**当前 Host** 算的下载地址，并隐去服务端路径。

        ``photo_id`` 一旦泄漏就等于给出了图片本身，所以路径从不对外暴露；下载地址每次
        请求重算，客户端换网络（Wi-Fi → 4G）后仍然能取到图。
        """
        origin = (base or self._request_public_base()).rstrip("/")
        photo_id = str(info.get("photo_id") or "")
        quoted = quote(photo_id, safe="")
        payload = {key: value for key, value in info.items() if key != "path"}
        payload["url"] = "%s/api/photos/%s" % (origin, quoted)
        payload["download_url"] = "%s/api/photos/%s?download=1" % (origin, quoted)
        return payload

    def _upload_photo(self) -> None:
        """POST /api/photos：上传一张照片进照片库，返回 photo_id 与下载地址。"""
        store = self._photo_store()
        payload = self._read_json_body()
        # 三种写法都接：客户端可能是从 <input type=file> 读的 data URL，也可能是裸 base64
        raw: Optional[str] = None
        for key in ("image", "base64", "data_url"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                raw = value.strip()
                break
        if raw is None:
            raise ApiError(400, "缺少照片内容：请提供 image / base64 / data_url 之一")
        if raw.startswith(DATA_URL_PREFIX):
            blob = _decode_data_url(raw)
        else:
            try:
                blob = base64.b64decode(raw, validate=False)
            except Exception as exc:  # noqa: BLE001 - binascii.Error 等
                raise ApiError(400, f"照片 base64 无法解码：{exc}") from exc
            if not blob:
                raise ApiError(400, "照片 base64 解码后为空")
        filename = payload.get("filename")
        if filename is not None and not isinstance(filename, str):
            raise ApiError(400, "字段 filename 必须是字符串")
        info = store.save(blob, filename)
        self._send_json({"ok": True, "photo": self._photo_payload(info)}, 200)

    def _list_photos(self) -> None:
        """GET /api/photos：按上传时间倒序列出照片库。"""
        store = self._photo_store()
        base = self._request_public_base()
        photos = [self._photo_payload(info, base) for info in store.list()]
        self._send_json({"ok": True, "count": len(photos), "photos": photos}, 200)

    def _serve_photo(self, raw_id: str, query: Dict[str, List[str]]) -> None:
        """GET /api/photos/<id>：图片流；``?download=1`` 走 attachment。"""
        store = self._photo_store()
        photo_id = unquote(str(raw_id or "")).strip()
        # 先做 id 白名单：非法 id 一律 400，绝不让它进入文件系统查找
        if not PhotoStore._valid_id(photo_id):
            raise ApiError(400, "照片 id 非法：只允许 4..64 个 [A-Za-z0-9_-] 字符")
        target = store.path_of(photo_id)
        if target is None:
            raise ApiError(404, f"照片不存在：{photo_id}")
        info = store.describe(photo_id) or {}
        content_type = str(info.get("mime") or mimetypes.guess_type(target)[0]
                           or "application/octet-stream")
        try:
            size = os.path.getsize(target)
        except OSError as exc:
            raise ApiError(500, f"读取照片失败：{exc}") from exc
        headers: List[Tuple[str, str]] = [("Cache-Control", "no-store")]
        if _as_bool((query.get("download") or [""])[0]):
            name = _photo_display_name(info.get("filename"), os.path.splitext(target)[1].lstrip("."))
            ascii_name = name.encode("ascii", "replace").decode("ascii").replace('"', "_")
            headers.append(
                (
                    "Content-Disposition",
                    "attachment; filename=\"%s\"; filename*=UTF-8''%s"
                    % (ascii_name, quote(name, safe="")),
                )
            )
        # 与 /files 一样走固定 Content-Length + 分块，十几 MB 的原图不进内存
        self._send_file_stream(target, content_type, size, headers)

    def _delete_photo(self, raw_id: str) -> None:
        """DELETE /api/photos/<id>：删图片 + sidecar；不存在回 404。"""
        store = self._photo_store()
        photo_id = unquote(str(raw_id or "")).strip()
        if not PhotoStore._valid_id(photo_id):
            raise ApiError(400, "照片 id 非法：只允许 4..64 个 [A-Za-z0-9_-] 字符")
        if not store.delete(photo_id):
            raise ApiError(404, f"照片不存在：{photo_id}")
        self._send_json({"ok": True, "deleted": True, "photo_id": photo_id}, 200)

    def _serve_file(self, raw_name: str, query: Dict[str, List[str]]) -> None:
        """从 output_dir 下载图片；只允许纯文件名，必须防路径穿越。"""
        app = self.app or {}
        name = unquote(raw_name or "")
        if not name or name in (".", "..") or "/" in name or "\\" in name or ".." in name:
            raise ApiError(400, "文件名非法：只允许输出目录下的纯文件名")
        if os.path.basename(name) != name:
            raise ApiError(400, "文件名非法：不允许路径分隔符")
        out_dir = os.path.realpath(str(app.get("output_dir") or "./outputs"))
        target = os.path.realpath(os.path.join(out_dir, name))
        if target != os.path.join(out_dir, name) or not target.startswith(out_dir + os.sep):
            raise ApiError(400, "文件名非法：疑似路径穿越")
        if not os.path.isfile(target):
            raise ApiError(404, f"文件不存在：{name}")
        content_type = mimetypes.guess_type(target)[0] or "application/octet-stream"
        try:
            size = os.path.getsize(target)
        except OSError as exc:
            raise ApiError(500, f"读取文件失败：{exc}") from exc
        headers: List[Tuple[str, str]] = [("Cache-Control", "no-store")]
        if _as_bool((query.get("download") or [""])[0]):
            ascii_name = name.encode("ascii", "replace").decode("ascii").replace('"', "_")
            headers.append(
                (
                    "Content-Disposition",
                    "attachment; filename=\"%s\"; filename*=UTF-8''%s"
                    % (ascii_name, quote(name, safe="")),
                )
            )
        # 固定 Content-Length + 64KB 分块：几百 MB 的图不再整只读进内存（S10）
        self._send_file_stream(target, content_type, size, headers)


# --------------------------------------------------------------------------- #
# 启动
# --------------------------------------------------------------------------- #
def make_server(
    host: str = "127.0.0.1",
    port: int = 8080,
    base_url: str = DEFAULT_BASE_URL,
    output_dir: str = "./outputs",
    template_path: Optional[str] = None,
    *,
    manager: Optional[ServiceManager] = None,
    restore_scheduler: Optional["RestoreScheduler"] = None,
    scheduler_enabled: bool = False,
    public_base_url: Optional[str] = None,
    config: Optional[Dict[str, Any]] = None,
    token: Optional[str] = None,
    cors_origin: str = "*",
    state_dir: str = "./state",
    job_retention: int = DEFAULT_JOB_RETENTION,
    photos_dir: Optional[str] = None,
) -> ThreadingHTTPServer:
    """创建 HTTP 服务（向后兼容：老的五参数调用仍然可用）。

    不给 ``manager`` 时退化为"不编排"模式：``/api/generate`` 直接用当前运行
    的 ComfyUI 生成，服务卡片为空，``/api/services`` 返回 ``manager=None`` 提示。
    """
    template = load_template(template_path)
    try:
        os.makedirs(output_dir, exist_ok=True)
    except OSError as exc:
        # 输出目录问题必须在"监听"之前暴露，不能混成"无法监听 host:port"
        raise OutputDirError(
            "无法创建/使用输出目录 %s —— %s" % (os.path.abspath(output_dir), exc)
        ) from exc
    client = ComfyClient(base_url)
    # 异步任务表：加载 <state-dir>/jobs.json（缺失/损坏都不会让服务起不来）
    job_store = JobStore(state_dir, retention=job_retention)
    # 照片库：长期保存的参考照片（助手全身照片上传一次，之后按 photo_id 取用）
    try:
        photo_store = PhotoStore(photos_dir or os.path.join(PROJECT_DIR, PHOTOS_DIRNAME))
    except OSError as exc:
        # 与输出目录同理：目录问题必须在"监听"之前报准，不能混成"无法监听 host:port"
        raise OutputDirError(
            "无法创建/使用照片库目录 %s —— %s"
            % (os.path.abspath(photos_dir or os.path.join(PROJECT_DIR, PHOTOS_DIRNAME)), exc)
        ) from exc
    cfg: Dict[str, Any] = dict(config or {})
    cfg.setdefault("generate_engine", "cli")
    cfg.setdefault("generate_timeout", DEFAULT_TIMEOUT)
    cfg.setdefault("restore_target", "llm" if scheduler_enabled else "none")
    cfg.setdefault("restore_mode", "background")
    cfg.setdefault("restore_delay", 3.0)
    ComfyApiHandler.app = {
        "client": client,
        "template": template,
        "template_path": template_path or DEFAULT_TEMPLATE,
        "output_dir": os.path.abspath(output_dir),
        "host": host,
        "port": port,
        "manager": manager,
        "restore_scheduler": restore_scheduler,
        "scheduler_enabled": bool(scheduler_enabled) and manager is not None,
        "public_base_url": public_base_url,
        "config": cfg,
        # 可选鉴权 / CORS：默认 token=None（不鉴权）、cors_origin="*"（与旧行为一致）
        "token": token or None,
        "cors_origin": cors_origin or "*",
        # 异步任务表（POST /api/generate/async 与 GET /api/jobs 从这里取）
        "job_store": job_store,
        "state_dir": job_store.state_dir,
        # 照片库（POST/GET/DELETE /api/photos，以及 /api/generate 的 full_body_photo_id）
        "photo_store": photo_store,
        "photos_dir": photo_store.root,
    }
    httpd = ThreadingHTTPServer((host, port), ComfyApiHandler)
    httpd.daemon_threads = True
    return httpd


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="server.py",
        description="Qwen-Image-Edit 提交器 HTTP API + 显存编排台（标准库 http.server）",
    )
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认 127.0.0.1")
    parser.add_argument("--port", type=int, default=8080, help="监听端口，默认 8080")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"ComfyUI 地址，默认 {DEFAULT_BASE_URL}")
    parser.add_argument("--output-dir", default="./outputs", help="结果图片保存目录，默认 ./outputs")
    parser.add_argument(
        "--state-dir", default="./state",
        help="异步任务表（jobs.json）保存目录，默认 ./state（与 --output-dir 一样相对 CWD）",
    )
    parser.add_argument(
        "--job-retention", type=int, default=DEFAULT_JOB_RETENTION,
        help=f"终态任务最多保留条数，默认 {DEFAULT_JOB_RETENTION}"
             "（按 created_at 淘汰最旧的，图片文件不删）",
    )
    parser.add_argument(
        "--photos-dir", default=None,
        help="照片库目录（长期保存的参考照片，POST /api/photos 落在来这里），"
             f"默认 {os.path.join(PROJECT_DIR, PHOTOS_DIRNAME)}",
    )
    parser.add_argument("--template", default=None, help=f"API 模板路径，默认 {DEFAULT_TEMPLATE}")
    # ---- 资源编排 ----
    parser.add_argument(
        "--auto-start", dest="auto_start", action="store_true", default=True,
        help="启动后在后台线程把机器切到 llm 模式（默认开启）",
    )
    parser.add_argument(
        "--no-auto-start", dest="auto_start", action="store_false",
        help="不在启动时做模式编排",
    )
    parser.add_argument(
        "--no-scheduler", dest="scheduler", action="store_false", default=True,
        help="关闭自动编排：/api/generate 不切模式，直接使用当前运行的 ComfyUI",
    )
    parser.add_argument(
        "--service-dry-run", action="store_true",
        help="透传 ServiceManager(dry_run=True)：只模拟启停，不碰真实进程与显存（联调用）",
    )
    parser.add_argument(
        "--generate-engine", choices=("cli", "internal"), default="cli",
        help="生成引擎：cli = 子进程跑 cli.py --json（默认）；internal = 进程内 runner.generate",
    )
    parser.add_argument("--llm-script", default="run-HuiHuiQwen3827B.sh", help="LLM 启动脚本")
    parser.add_argument("--llm-port", type=int, default=6008, help="LLM 端口，默认 6008")
    parser.add_argument("--tts-script", default="run-ttsQwenQ8.sh", help="TTS 启动脚本")
    parser.add_argument("--tts-port", type=int, default=9080, help="TTS 端口，默认 9080")
    parser.add_argument("--comfy-dir", default="/root/comfy/ComfyUI", help="ComfyUI 安装目录")
    parser.add_argument("--comfy-python", default="/root/comfyenv/bin/python", help="ComfyUI 解释器")
    parser.add_argument("--comfy-port", type=int, default=8188, help="ComfyUI 端口，默认 8188")
    parser.add_argument("--comfy-cmd", default=None, help="自定义 ComfyUI 启动命令（默认交给 default_specs）")
    parser.add_argument("--logs-dir", default="./logs", help="服务日志目录，默认 ./logs")
    parser.add_argument(
        "--public-base-url", default=None,
        help="下载地址的外部基址；缺省按请求 Host 头推导，再退回监听地址",
    )
    parser.add_argument(
        "--token", default=None,
        help="可选访问令牌：设置后 /api/* 与 /files/* 需要 X-Auth-Token 头或 ?token=；默认不鉴权",
    )
    parser.add_argument(
        "--cors-origin", default="*",
        help="Access-Control-Allow-Origin 的值，默认 *（对外暴露时可收紧成具体源）",
    )
    parser.add_argument("--generate-timeout", type=float, default=DEFAULT_TIMEOUT,
                        help=f"单次生成最长等待秒数，默认 {int(DEFAULT_TIMEOUT)}")
    parser.add_argument("--vram-comfy-min-mib", type=int, default=16000,
                        help="拉起 ComfyUI 前要求的最小空闲显存（MiB），默认 16000")
    parser.add_argument("--vram-llm-min-mib", type=int, default=12000,
                        help="拉起 LLM 前要求的最小空闲显存（MiB），默认 12000")
    parser.add_argument("--restore-target", choices=("llm", "none"), default="llm",
                        help="生成结束后恢复到的模式，默认 llm")
    parser.add_argument("--restore-mode", choices=("background", "blocking"), default="background",
                        help="恢复方式：background（默认，回包后后台恢复）或 blocking")
    parser.add_argument("--restore-delay", type=float, default=3.0,
                        help="后台恢复前的合并等待秒数，默认 3.0")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = _build_arg_parser().parse_args(argv)
    if args.job_retention < 1:
        print("[error] --job-retention 必须 >= 1", file=sys.stderr)
        return 2

    # ---- 资源编排器（服务描述 + 互斥锁）----
    specs = default_specs(
        PROJECT_DIR,
        args.logs_dir,
        comfy_dir=args.comfy_dir,
        comfy_python=args.comfy_python,
        comfy_port=args.comfy_port,
        llm_script=args.llm_script,
        tts_script=args.tts_script,
        llm_port=args.llm_port,
        tts_port=args.tts_port,
        comfy_cmd=args.comfy_cmd,
    )
    manager: Optional[ServiceManager] = None
    restore: Optional[RestoreScheduler] = None
    try:
        manager = ServiceManager(
            specs,
            dry_run=args.service_dry_run,
            comfy_min_free_mib=args.vram_comfy_min_mib,
            llm_min_free_mib=args.vram_llm_min_mib,
        )
    except Exception as exc:  # noqa: BLE001 - 初始化失败必须给出可读原因
        print(f"[error] 初始化资源编排器失败 —— {exc}", file=sys.stderr)
        return 2
    if args.scheduler:
        restore = RestoreScheduler(
            manager,
            target=args.restore_target,
            mode=args.restore_mode,
            grace=args.restore_delay,
        )

    config: Dict[str, Any] = {
        "auto_start": bool(args.auto_start),
        "scheduler_enabled": bool(args.scheduler),
        "generate_engine": args.generate_engine,
        "generate_timeout": args.generate_timeout,
        "restore_target": args.restore_target,
        "restore_mode": args.restore_mode,
        "restore_delay": args.restore_delay,
        "comfy_port": args.comfy_port,
        "llm_port": args.llm_port,
        "tts_port": args.tts_port,
        "dry_run": bool(args.service_dry_run),
        "logs_dir": args.logs_dir,
    }

    try:
        httpd = make_server(
            args.host,
            args.port,
            args.base_url,
            args.output_dir,
            args.template,
            manager=manager,
            restore_scheduler=restore,
            scheduler_enabled=bool(args.scheduler),
            public_base_url=args.public_base_url,
            config=config,
            token=args.token,
            cors_origin=args.cors_origin,
            state_dir=args.state_dir,
            job_retention=args.job_retention,
            photos_dir=args.photos_dir,
        )
    except OutputDirError as exc:
        # 阶段 1：输出目录不可用 —— 别说成"无法监听"（那是完全不同的问题）
        print(f"[error] {exc}", file=sys.stderr)
        return 2
    except OSError as exc:
        # 阶段 2：绑定/监听失败
        print(
            "[error] 无法监听 %s:%s —— %s（端口被占用或地址不可用）"
            % (args.host, args.port, exc),
            file=sys.stderr,
        )
        return 2
    except (ValueError, FileNotFoundError) as exc:
        print(f"[error] 模板加载失败 —— {exc}", file=sys.stderr)
        return 2

    # ---- 启动信息 ----
    print(f"{SERVER_NAME} 已启动：http://{args.host}:{args.port}")
    print(f"  WebUI     : http://{args.host}:{args.port}/")
    print(f"  ComfyUI   : {args.base_url}  (受管端口 {args.comfy_port})")
    print(f"  LLM / TTS : {args.llm_port} / {args.tts_port}（脚本 {args.llm_script} / {args.tts_script}）")
    print(f"  输出目录  : {os.path.abspath(args.output_dir)}")
    print(
        "  状态目录  : %s（异步任务表 jobs.json，终态保留 %d 条）"
        % (os.path.abspath(args.state_dir), args.job_retention)
    )
    print(
        "  照片库    : %s（POST/GET/DELETE /api/photos，参考图上限 %d 张）"
        % (
            os.path.abspath(args.photos_dir or os.path.join(PROJECT_DIR, PHOTOS_DIRNAME)),
            MAX_REFERENCE_IMAGES,
        )
    )
    print(f"  日志目录  : {os.path.abspath(args.logs_dir)}")
    print(f"  模板      : {args.template or DEFAULT_TEMPLATE}")
    print(
        "  编排      : scheduler_enabled=%s engine=%s restore=%s(%s, %.1fs) dry_run=%s"
        % (
            bool(args.scheduler),
            args.generate_engine,
            args.restore_target,
            args.restore_mode,
            args.restore_delay,
            bool(args.service_dry_run),
        )
    )
    if args.token:
        print("  鉴权      : 已启用 --token（/api/* 与 /files/* 需 X-Auth-Token 或 ?token=）")
    else:
        print("  鉴权      : 未启用（任何人可调用；对外暴露请加 --token 或反向代理）")
    print("  CORS      : Access-Control-Allow-Origin = %s" % args.cors_origin)
    if args.service_dry_run:
        print("  ! DRY-RUN：不会启停任何真实服务，也不会占用显存")
    if args.auto_start and manager is not None:
        print(
            "  ! --auto-start 会立即把机器切到 LLM 模式："
            "**会先停止正在运行的 ComfyUI（若有），其排队/进行中的任务会被中断**"
        )
    print("  Ctrl+C 退出（不会停掉已经在跑的服务）")
    sys.stdout.flush()   # nohup/systemd 重定向到文件时 stdout 是块缓冲，先刷一次

    # ---- 自动编排初始模式：daemon 线程，绝不阻塞 serve_forever ----
    if args.auto_start and manager is not None:
        def _auto_start() -> None:
            sys.stderr.write(
                "[auto-start] 正在切到 llm 模式：会先停止正在运行的 ComfyUI（若有），"
                "其排队/进行中的任务会被中断\n"
            )
            try:
                sys.stderr.flush()
            except Exception:
                pass
            try:
                result = manager.ensure_mode(SERVICE_LLM)  # type: ignore[union-attr]
                sys.stderr.write(
                    "[auto-start] 已切到 llm 模式：switched=%s steps=%d\n"
                    % (result.get("switched"), len(result.get("steps") or []))
                )
            except Exception as exc:  # noqa: BLE001 - 失败也不能让进程退出
                detail = getattr(exc, "detail", None)
                sys.stderr.write("[auto-start] 切换到 llm 模式失败：%s\n" % exc)
                if detail:
                    try:
                        sys.stderr.write(
                            "[auto-start] detail=%s\n"
                            % json.dumps(detail, ensure_ascii=False)[:4000]
                        )
                    except Exception:
                        pass
            finally:
                try:
                    sys.stderr.flush()
                except Exception:
                    pass

        threading.Thread(target=_auto_start, name="auto-start", daemon=True).start()

    # ---- SIGTERM 优雅退出（shutdown 必须从别的线程调，否则死锁）----
    def _on_signal(signum: int, _frame: Any) -> None:
        sys.stderr.write("[server] 收到信号 %s，正在退出…\n" % signum)
        threading.Thread(target=httpd.shutdown, name="shutdown", daemon=True).start()

    try:
        signal.signal(signal.SIGTERM, _on_signal)
        signal.signal(signal.SIGINT, signal.default_int_handler)
    except (ValueError, OSError):  # pragma: no cover - 非主线程启动时无法注册
        pass

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n收到 Ctrl+C，正在退出 …")
    finally:
        if restore is not None:
            restore.shutdown()
        if manager is not None:
            # stop_all=False：LLM/TTS/ComfyUI 继续在后台活着，只释放编排状态
            manager.shutdown(stop_all=False)
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
