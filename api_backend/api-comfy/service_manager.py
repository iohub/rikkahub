#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GPU 资源互斥编排器：在单卡 RTX 3090（24G）上按需切换 LLM/TTS 与 ComfyUI。

背景
----
本机只有一张 RTX 3090（24576 MiB）。ComfyUI 峰值显存约 22.5 GB，而
``llama-server``（27B IQ3_S，``-ngl 99``）约 13 GB、``tts-server`` 约 3 GB，
**两组服务无法同时驻留显存**。所以任何"用 ComfyUI 生成"的请求都必须先确认
LLM/TTS 已退出、显存真的被释放，生成结束后再把 LLM/TTS 恢复起来。

本模块只做编排（进程启停 / 端口探活 / 显存等待 / 互斥锁），不掺任何 HTTP 逻辑，
便于被 ``server.py`` 调用，也便于 ``python3 service_manager.py --self-test``
在完全不碰 GPU 的前提下自检。

设计要点
--------
* **只读 /proc、只用标准库**：进程发现靠 ``/proc/<pid>/{cmdline,cwd,stat}``，
  端口探测靠 ``socket.create_connection`` / ``urllib``，显存靠 ``nvidia-smi``。
  绝不修改 ``/root/comfy/ComfyUI`` 安装目录里的任何文件。
* **while true 重启壳（本模块的技术核心）**：``run-ttsQwenQ8.sh`` 是
  ``while true; do tts-server ...; done``。只 kill ``tts-server`` 时 bash 会在
  毫秒级把它重新拉起，``pkill`` 永远杀不干净。所以停止时先把 **"壳"**
  （cmdline 里带 ``.sh`` 的那个 bash）``SIGKILL`` 掉，让循环彻底消失，再对
  **worker**（``llama-server`` / ``tts-server`` / ``main.py``）发 ``SIGTERM``
  走优雅退出；超时仍不消失再全部 ``SIGKILL``，最后**重新扫描**确认没有"复活"。
* **session / pgid 整组回收**：启动用 ``start_new_session=True``，子进程自成
  session 且 PID == PGID，服务在 ``server.py`` 退出后继续存活（不注册 atexit）；
  回收时优先 ``os.killpg(pgid, sig)``，壳和 worker 一起走，不留孤儿占显存。
* **编排锁**：``threading.RLock`` + ``_busy`` 标志。生成期间 ``comfy_session``
  一直持锁，其它线程的 start/stop/ensure_mode 在 ``lock_wait``（默认 0.25s）后
  拿到 ``ServiceBusy``（server 映射 409）。而 ``service_status`` / ``snapshot`` /
  ``current_mode`` / ``vram`` **从不开锁**，UI 轮询永远不会被生成任务卡死。
* **dry_run**：把所有 spawn/kill/等待替换成"内存里的模拟运行集合"，不碰任何真实
  进程与 GPU，用于 server 侧接口联调与自检。
"""

from __future__ import annotations

import argparse
import contextlib
import os
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Dict, Iterator, List, Optional, Sequence, Tuple

__all__ = [
    "SERVICE_LLM",
    "SERVICE_TTS",
    "SERVICE_COMFY",
    "SERVICE_ORDER",
    "ServiceError",
    "ServiceBusy",
    "ServiceSpec",
    "default_specs",
    "iter_pids",
    "read_cmdline",
    "read_cwd",
    "find_pids",
    "pid_ppid",
    "pid_alive",
    "port_open",
    "http_ok",
    "nvidia_vram",
    "wait_until",
    "tail_file",
    "ServiceManager",
    "run_self_test",
]

# --------------------------------------------------------------------------- #
# 常量
# --------------------------------------------------------------------------- #
SERVICE_LLM = "llm"
SERVICE_TTS = "tts"
SERVICE_COMFY = "comfy"
SERVICE_ORDER: Tuple[str, ...] = (SERVICE_LLM, SERVICE_TTS, SERVICE_COMFY)

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
COMFY_DIR = "/root/comfy/ComfyUI"
COMFY_PYTHON = "/root/comfyenv/bin/python"
HOST = "127.0.0.1"

# 单卡 3090 总显存，仅用于日志与默认阈值推导
TOTAL_VRAM_MIB = 24576

# 启动超时（秒）：模型都在 FUSE 网络盘上，首次加载慢，给足余量
LLM_START_TIMEOUT = 600.0
TTS_START_TIMEOUT = 300.0
COMFY_START_TIMEOUT = 420.0

# tail_file 保护：最多回溯 1MB / 单行最多 4000 字符
TAIL_MAX_BYTES = 1024 * 1024
TAIL_MAX_LINE = 4000
TAIL_BLOCK = 8192

# 本地探活必须绕开 http_proxy，否则 127.0.0.1 可能被代理劫持
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# --------------------------------------------------------------------------- #
# 异常
# --------------------------------------------------------------------------- #
class _ServiceBase(RuntimeError):
    """start/stop 类错误的公共基类：始终带可序列化的 ``detail``。"""

    def __init__(self, message: str, detail: Optional[Dict[str, Any]] = None) -> None:
        super().__init__(message)
        self.message = str(message)
        self.detail: Dict[str, Any] = dict(detail or {})

    def to_dict(self) -> Dict[str, Any]:
        return {"ok": False, "error": self.message, "detail": self.detail}


class ServiceBusy(_ServiceBase):
    """编排锁被占用（有生成任务在跑），server 侧映射 HTTP 409。"""


class ServiceError(_ServiceBase):
    """启停 / 等待失败，``detail`` 里带 ``status`` 与 ``log_tail`` 便于排错。"""


# --------------------------------------------------------------------------- #
# 进程：只读 /proc
# --------------------------------------------------------------------------- #
def iter_pids() -> Iterator[int]:
    """遍历当前 PID 命名空间下的所有进程号（读 /proc，无需 psutil）。"""
    try:
        entries = os.listdir("/proc")
    except OSError:
        return
    for entry in entries:
        if entry.isdigit():
            yield int(entry)


def read_cmdline(pid: int) -> List[str]:
    """读 ``/proc/<pid>/cmdline``（NUL 分隔）。

    僵尸进程与内核线程的 cmdline 为空，返回 ``[]``——调用方据此自然跳过。
    """
    try:
        with open("/proc/%d/cmdline" % int(pid), "rb") as fh:
            raw = fh.read(64 * 1024)
    except (OSError, ValueError):
        return []
    if not raw:
        return []
    items: List[str] = []
    for chunk in raw.split(b"\x00"):
        if not chunk:
            continue
        items.append(chunk.decode("utf-8", "replace"))
    return items


def read_cwd(pid: int) -> Optional[str]:
    """读 ``/proc/<pid>/cwd`` 软链；进程已退出或无权限时返回 None。"""
    try:
        return os.readlink("/proc/%d/cwd" % int(pid))
    except (OSError, ValueError):
        return None


def _read_stat_fields(pid: int) -> List[bytes]:
    """``/proc/<pid>/stat`` 的字段列表（已切掉 ``(comm)``，含空格/括号也不怕）。"""
    try:
        with open("/proc/%d/stat" % int(pid), "rb") as fh:
            data = fh.read(8192)
    except (OSError, ValueError):
        return []
    cut = data.rfind(b")")
    if cut < 0:
        return []
    return data[cut + 2:].split()


def pid_ppid(pid: int) -> Optional[int]:
    """父进程号；进程不存在返回 None。"""
    fields = _read_stat_fields(pid)
    if len(fields) < 2:
        return None
    try:
        return int(fields[1])
    except (TypeError, ValueError):
        return None


def pid_alive(pid: int) -> bool:
    """进程是否真的还活着。

    注意把**僵尸态**算作"已死"：``os.kill(pid, 0)`` 对僵尸同样成功，若按它判断，
    stop 会一直以为进程没走而白等到超时。
    """
    if pid is None or int(pid) <= 0:
        return False
    fields = _read_stat_fields(pid)
    if not fields:
        return False
    return fields[0] != b"Z"


def _ancestor_pids(start: Optional[int] = None) -> set:
    """自身 + 所有祖先 PID，用于 find_pids 排除误伤（比如杀掉调用者的 shell）。"""
    seen: set = set()
    cur = os.getpid() if start is None else int(start)
    guard = 0
    while cur and cur > 0 and cur not in seen and guard < 64:
        seen.add(cur)
        cur = pid_ppid(cur) or 0
        guard += 1
    return seen


def _pid_is_wrapper(pid: int) -> bool:
    """是否"重启壳"：cmdline 里带 ``.sh`` 的 bash/sh。

    ``run-ttsQwenQ8.sh`` 是 ``while true`` 循环，壳不死 worker 就永远会再生，
    所以必须先识别出来先杀。
    """
    args = read_cmdline(pid)
    if len(args) < 2:
        return False
    tail = " ".join(args[1:])
    return ".sh" in tail


def _pid_match_texts(pid: int) -> List[str]:
    """构造用于子串匹配的候选文本。

    除了原始 cmdline，还补上"相对参数 × cwd"的绝对路径：ComfyUI 是被
    ``python main.py`` 拉起来的（argv 里只有相对名），只有把 cwd 拼上去，
    ``/root/comfy/ComfyUI/main.py`` 这种绝对路径 pattern 才能命中。
    """
    args = read_cmdline(pid)
    if not args:
        return []
    texts: List[str] = [" ".join(args)]
    texts.extend(args)
    cwd = read_cwd(pid)
    if cwd:
        for arg in args:
            if arg and not arg.startswith("/") and "\x00" not in arg:
                texts.append(os.path.normpath(os.path.join(cwd, arg)))
    return texts


def find_pids(
    patterns: Sequence[str],
    *,
    cwd: Optional[str] = None,
    exclude: Sequence[int] = (),
) -> List[int]:
    """按 cmdline 子串找进程。

    * 任一 pattern 是 cmdline（或其单参数、或其 cwd 绝对化形式）的子串即命中；
    * ``cwd`` 非空时只保留 cwd 相同的进程（用于把 ``main.py`` 钉死在 ComfyUI 目录）；
    * 自动排除自身与全部祖先，避免误杀调用链上的 shell / python。

    这是只读操作，任何情况下都不会 kill 别人。
    """
    pats = [str(p) for p in patterns if p]
    if not pats:
        return []
    skip = {int(x) for x in exclude if x is not None}
    skip |= _ancestor_pids()
    want_cwd = os.path.realpath(cwd) if cwd else None

    hits: List[int] = []
    for pid in iter_pids():
        if pid in skip:
            continue
        if want_cwd is not None:
            proc_cwd = read_cwd(pid)
            if not proc_cwd or os.path.realpath(proc_cwd) != want_cwd:
                continue
        texts = _pid_match_texts(pid)
        if not texts:
            continue
        for pattern in pats:
            if any(pattern in text for text in texts):
                hits.append(pid)
                break
    return sorted(hits)


# --------------------------------------------------------------------------- #
# 网络 / 端口 / 显存
# --------------------------------------------------------------------------- #
def port_open(host: str, port: int, timeout: float = 0.3) -> bool:
    """TCP 能否建连。

    本机**没有 ss / netstat**，所以端口状态一律用 connect 试探；连不上即视为关闭。
    """
    try:
        with socket.create_connection((host, int(port)), timeout=max(0.05, float(timeout))):
            return True
    except (OSError, ValueError):
        return False


def http_ok(url: str, timeout: float = 2.0) -> bool:
    """HTTP 探活：2xx/3xx 视为 ok，一切异常（含 4xx/5xx 抛的 HTTPError）返回 False。"""
    if not url:
        return False
    try:
        with _OPENER.open(url, timeout=max(0.1, float(timeout))) as resp:
            code = getattr(resp, "status", None) or resp.getcode()
            try:
                resp.read(2048)  # 读一点点，帮助连接正常关闭
            except Exception:
                pass
        return code is not None and 200 <= int(code) < 400
    except Exception:
        return False


def nvidia_vram() -> Optional[Dict[str, Any]]:
    """读 GPU 显存占用。

    解析 ``nvidia-smi --query-gpu=memory.total,memory.used,memory.free`` 的
    ``noheader,nounits`` 输出；nvidia-smi 不存在 / 报错 / 输出异常时返回 None
    （调用方必须把 None 当作"跳过等待"，而不是失败）。
    """
    cmd = [
        "nvidia-smi",
        "--query-gpu=memory.total,memory.used,memory.free",
        "--format=csv,noheader,nounits",
    ]
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=6.0,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    text = (proc.stdout or b"").decode("utf-8", "replace").strip()
    if not text:
        return None
    first = text.splitlines()[0]
    parts = [p.strip() for p in first.split(",")]
    if len(parts) < 3:
        return None
    try:
        total = int(float(parts[0]))
        used = int(float(parts[1]))
        free = int(float(parts[2]))
    except (TypeError, ValueError):
        return None
    return {"total_mib": total, "used_mib": used, "free_mib": free, "raw": text}


def wait_until(predicate: Callable[[], bool], timeout: float, interval: float = 0.5) -> bool:
    """轮询直到 predicate 为真或超时。

    interval 被夹在 [0.05, 1.0]：本模块不允许出现超过 1s 的死等，全部走轮询，
    这样"服务提前就绪"能立刻返回，"服务崩了"也不会白等。
    """
    step = max(0.05, min(float(interval), 1.0))
    budget = max(0.0, float(timeout))
    deadline = time.monotonic() + budget
    while True:
        try:
            if predicate():
                return True
        except Exception:
            pass  # 轮询期间的瞬时错误（进程刚退出等）视为"还没到"
        left = deadline - time.monotonic()
        if left <= 0:
            return False
        time.sleep(min(step, left))


def tail_file(path: str, lines: int = 50) -> str:
    """读文件末尾 N 行（日志排错用）。

    大日志绝不整读：从文件尾部按 8KB 块往回 seek，最多回溯 ``TAIL_MAX_BYTES``；
    单行超过 ``TAIL_MAX_LINE`` 会被截断（防止一条巨大的 JSON/堆栈把 UI 撑爆）。
    文件不存在或不可读时返回空串，不抛异常。
    """
    want = max(1, int(lines))
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            if size <= 0:
                return ""
            pos = size
            data = b""
            while pos > 0 and (size - pos) < TAIL_MAX_BYTES:
                if data.count(b"\n") >= want + 1:
                    break
                step = min(TAIL_BLOCK, pos)
                pos -= step
                fh.seek(pos)
                data = fh.read(step) + data
    except (OSError, ValueError):
        return ""
    text = data.decode("utf-8", "replace")
    chunks = text.splitlines()
    if pos > 0 and chunks:
        chunks = chunks[1:]  # 第一行多半是被切断的半行，丢掉
    tail = chunks[-want:]
    return "\n".join(line[:TAIL_MAX_LINE] for line in tail)


# --------------------------------------------------------------------------- #
# 服务描述
# --------------------------------------------------------------------------- #
@dataclass
class ServiceSpec:
    """一个受管服务的静态描述（启动命令、工作目录、端口、日志）。

    ``patterns`` / ``match_cwd`` / ``health_url`` 是三个带默认值的扩展字段：
    前者决定"怎样在 /proc 里认出这个服务"，后者决定就绪探测走端口还是 HTTP。
    不传时由 ``default_specs`` 填好，老的调用方式（只给前 7 个参数）依然可用。
    """

    name: str
    label: str
    kind: str          # "script"（bash 跑 .sh）或 "cmd"（直接跑命令，shlex.split）
    target: str        # .sh 绝对路径 / 命令字符串
    cwd: str
    port: int
    log_path: str      # stdout/stderr 追加写入的日志
    start_timeout: float = 120.0
    vram_hint: str = ""  # 如 "~13 GB"
    # ---- 扩展字段（有默认值，不影响必需签名）----
    patterns: Tuple[str, ...] = ()   # find_pids 用的 cmdline 子串；空则用 target 的 basename
    match_cwd: bool = False          # True 时额外要求进程 cwd == spec.cwd（更严格）
    health_url: str = ""             # 非空时用 http_ok 探活，否则用 port_open


def default_specs(
    project_dir: str,
    logs_dir: str,
    *,
    comfy_dir: str = COMFY_DIR,
    comfy_python: str = COMFY_PYTHON,
    comfy_port: int = 8188,
    llm_script: str = "run-HuiHuiQwen3827B.sh",
    tts_script: str = "run-ttsQwenQ8.sh",
    llm_port: int = 6008,
    tts_port: int = 9080,
    comfy_cmd: Optional[str] = None,
) -> Dict[str, ServiceSpec]:
    """生成本机默认的三个服务描述。

    路径/端口全部可被参数覆盖（server.py 之后要暴露成 CLI 参数）。默认值对应本机实况：

    * ComfyUI：``/root/comfyenv/bin/python main.py --port 8188 --listen 127.0.0.1``，
      cwd 必须是安装目录，就绪判据是 ``GET /system_stats``；
    * LLM：``run-HuiHuiQwen3827B.sh`` 前台一次性命令（不循环），端口 6008；
    * TTS：``run-ttsQwenQ8.sh`` 内含 ``while true`` 无限重启（停止时要先杀壳），端口 9080。

    日志目录不存在会被创建（``os.makedirs``），日志一律追加写。
    """
    proj = os.path.abspath(project_dir or PROJECT_DIR)
    logs = os.path.abspath(logs_dir or os.path.join(proj, "logs"))
    try:
        os.makedirs(logs, exist_ok=True)
    except OSError:
        pass

    comfy_dir = os.path.abspath(comfy_dir or COMFY_DIR)
    llm_path = llm_script if os.path.isabs(llm_script) else os.path.join(proj, llm_script)
    tts_path = tts_script if os.path.isabs(tts_script) else os.path.join(proj, tts_script)
    cmd = comfy_cmd or "%s main.py --port %d --listen %s" % (comfy_python, int(comfy_port), HOST)

    # ComfyUI 的识别特征：main.py 的**绝对路径**。
    # 刻意不用裸 "main.py"（会误伤别的同名进程），也不用解释器路径
    # （PATH 里的 python3 就是 comfyenv 的，用它会把本项目的 server.py 也算进来）。
    # 相对启动（`python main.py`）靠 find_pids 的"相对参数 × cwd"补全来命中。
    comfy_main = os.path.join(comfy_dir, "main.py")
    comfy_patterns: Tuple[str, ...] = (comfy_main,)
    if comfy_cmd:
        extra = tuple(
            item if os.path.isabs(item) else os.path.join(comfy_dir, item)
            for item in shlex.split(comfy_cmd)
            if item.endswith((".py", ".sh"))
        )
        if extra:
            comfy_patterns = extra

    specs: Dict[str, ServiceSpec] = {
        SERVICE_LLM: ServiceSpec(
            name=SERVICE_LLM,
            label="Huihui-Qwen3.8-27B (llama-server)",
            kind="script",
            target=llm_path,
            cwd=proj,
            port=int(llm_port),
            log_path=os.path.join(logs, "llm.log"),
            start_timeout=LLM_START_TIMEOUT,
            vram_hint="~13 GB",
            patterns=(os.path.basename(llm_path), "llama-server"),
        ),
        SERVICE_TTS: ServiceSpec(
            name=SERVICE_TTS,
            label="Qwen3-TTS-1.7B (tts-server, while-true 壳)",
            kind="script",
            target=tts_path,
            cwd=proj,
            port=int(tts_port),
            log_path=os.path.join(logs, "tts.log"),
            start_timeout=TTS_START_TIMEOUT,
            vram_hint="~3 GB",
            patterns=(os.path.basename(tts_path), "tts-server"),
        ),
        SERVICE_COMFY: ServiceSpec(
            name=SERVICE_COMFY,
            label="ComfyUI 0.46.0 (Qwen-Image-Edit)",
            kind="cmd",
            target=cmd,
            cwd=comfy_dir,
            port=int(comfy_port),
            log_path=os.path.join(logs, "comfy.log"),
            start_timeout=COMFY_START_TIMEOUT,
            vram_hint="~22.5 GB",
            patterns=comfy_patterns,
            health_url="http://%s:%d/system_stats" % (HOST, int(comfy_port)),
        ),
    }
    return specs


# --------------------------------------------------------------------------- #
# 编排器
# --------------------------------------------------------------------------- #
def _build_cmd(spec: ServiceSpec) -> List[str]:
    """把 spec 变成 argv。

    ``{port}`` / ``{cwd}`` 占位符在解析前替换，方便 ``comfy_cmd`` 这种模板化命令。
    script 一律用 ``/bin/bash`` 执行（.sh 可能没有可执行位）。
    """
    target = str(spec.target or "").replace("{port}", str(spec.port)).replace("{cwd}", spec.cwd)
    kind = (spec.kind or "script").strip().lower()
    if kind == "script":
        if not os.path.isfile(target):
            raise ServiceError(
                "%s 的启动脚本不存在：%s" % (spec.name, target),
                detail={"name": spec.name, "target": target},
            )
        return ["/bin/bash", target]
    if kind == "cmd":
        parts = shlex.split(target)
        if not parts:
            raise ServiceError("%s 的启动命令为空" % spec.name, detail={"name": spec.name})
        if parts[0].startswith("/") and not os.access(parts[0], os.X_OK):
            raise ServiceError(
                "%s 的可执行文件不存在或无执行权限：%s" % (spec.name, parts[0]),
                detail={"name": spec.name, "cmd": parts, "exe": parts[0]},
            )
        return parts
    raise ServiceError(
        "%s 的 kind 非法：%r（只支持 script / cmd）" % (spec.name, spec.kind),
        detail={"name": spec.name, "kind": spec.kind},
    )


class ServiceManager:
    """GPU 互斥编排：谁用显存由这把锁说了算。

    线程模型：``_lock``（RLock）保护"切换动作"，生成期间由 ``comfy_session``
    长时间持有；``_busy`` 是给 HTTP 层看的"正在进行生成"标志。只读接口
    （``service_status`` / ``snapshot`` / ``current_mode`` / ``vram``）**从不加锁**，
    因此 UI 轮询不会被生成请求阻塞。``_proc_lock`` 只护住一行字典赋值，永不长时间持有。
    """

    def __init__(
        self,
        specs: Dict[str, ServiceSpec],
        *,
        dry_run: bool = False,
        stop_timeout: float = 30.0,
        vram_wait_timeout: float = 90.0,
        comfy_min_free_mib: int = 16000,
        llm_min_free_mib: int = 12000,
        lock_wait: float = 0.25,
        logger: Optional[Callable[[str], None]] = None,
    ) -> None:
        self._specs: Dict[str, ServiceSpec] = dict(specs or {})
        self._dry_run = bool(dry_run)
        self._stop_timeout = max(1.0, float(stop_timeout))
        self._vram_wait_timeout = max(1.0, float(vram_wait_timeout))
        self._comfy_min_free_mib = int(comfy_min_free_mib)
        self._llm_min_free_mib = int(llm_min_free_mib)
        self._lock_wait = max(0.0, float(lock_wait))
        self._logger = logger

        self._lock = threading.RLock()
        self._busy = False
        self._proc_lock = threading.Lock()
        self._procs: Dict[str, subprocess.Popen] = {}
        self._pgids: Dict[str, int] = {}
        self._exit_codes: Dict[str, Optional[int]] = {}
        self._last_error: Dict[str, Optional[str]] = {name: None for name in self._specs}
        self._dry_running: Dict[str, bool] = {name: False for name in self._specs}

        # 日志目录先建好：服务被拉起后就没有机会补建了
        for spec in self._specs.values():
            try:
                log_dir = os.path.dirname(spec.log_path)
                if log_dir:
                    os.makedirs(log_dir, exist_ok=True)
            except OSError as exc:
                self._log("创建日志目录失败 %s：%s" % (spec.log_path, exc))
        if self._dry_run:
            self._log("ServiceManager 运行在 dry_run 模式：不会启停任何真实进程")

    # ------------------------------------------------------------------ #
    # 只读接口（绝不加编排锁）
    # ------------------------------------------------------------------ #
    @property
    def dry_run(self) -> bool:
        """是否模拟模式。"""
        return self._dry_run

    def busy(self) -> bool:
        """是否有生成任务正占用编排锁（server 用它决定要不要拒绝新请求）。"""
        return bool(self._busy)

    def vram(self) -> Optional[Dict[str, Any]]:
        """当前显存占用；nvidia-smi 不可用时返回 None。"""
        try:
            return nvidia_vram()
        except Exception:
            return None

    def current_mode(self) -> str:
        """当前实际形态：``llm`` / ``comfy`` / ``mixed`` / ``idle``。

        ``mixed`` 说明两组服务同时驻留——在本机 24G 显存下通常会 OOM，
        出现它往往意味着有人手工起过服务。
        """
        llm_up = self._is_up(SERVICE_LLM)
        tts_up = self._is_up(SERVICE_TTS)
        comfy_up = self._is_up(SERVICE_COMFY)
        if comfy_up and (llm_up or tts_up):
            return "mixed"
        if comfy_up:
            return "comfy"
        if llm_up or tts_up:
            return "llm"
        return "idle"

    def service_status(self, name: str) -> Dict[str, Any]:
        """单个服务的实时状态（只读，不加编排锁）。

        ``dry_run`` 下不触发任何真实探测，直接反映内存里的模拟运行集合，
        这样接口联调用例的结果是确定的。
        """
        spec = self._spec(name)
        if self._dry_run:
            running = bool(self._dry_running.get(spec.name))
            pids: List[int] = []
            port_up = running
        else:
            pids = self._match_pids(spec)
            port_up = self._probe(spec, 1.0)
            running = bool(pids) or port_up
        return {
            "name": spec.name,
            "label": spec.label,
            "kind": spec.kind,
            "port": int(spec.port),
            "running": bool(running),
            "pids": pids,
            "port_open": bool(port_up),
            "log_path": spec.log_path,
            "start_timeout": float(spec.start_timeout),
            "vram_hint": spec.vram_hint,
            "managed": (spec.name in self._procs) or (self._dry_run and running),
            "last_error": self._last_error.get(spec.name),
        }

    def snapshot(self) -> Dict[str, Any]:
        """一次性给 UI 的全景快照（只读，不加编排锁）。"""
        services: List[Dict[str, Any]] = []
        seen = set()
        for name in SERVICE_ORDER:
            if name in self._specs:
                services.append(self._status_safe(name))
                seen.add(name)
        for name in self._specs:
            if name not in seen:
                services.append(self._status_safe(name))
        return {
            "ok": True,
            "mode": self.current_mode(),
            "busy": self.busy(),
            "vram": self.vram(),
            "services": services,
            "lock_wait": self._lock_wait,
            "dry_run": self._dry_run,
        }

    def log_tail(self, name: str, lines: int = 50) -> str:
        """读某服务日志末尾若干行；服务名不认识时返回空串（用于排错，不抛异常）。"""
        try:
            spec = self._spec(name)
        except ServiceError:
            return ""
        return tail_file(spec.log_path, lines)

    def attach_logger(self, logger: Optional[Callable[[str], None]]) -> None:
        """替换日志回调（默认写 stderr）。"""
        self._logger = logger

    # ------------------------------------------------------------------ #
    # 内部只读工具
    # ------------------------------------------------------------------ #
    def _log(self, message: str) -> None:
        """统一日志出口：带时间戳，回调异常不能影响编排主流程。"""
        line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
        if self._logger is not None:
            try:
                self._logger(line)
                return
            except Exception:
                pass
        try:
            sys.stderr.write("[service_manager] %s\n" % line)
            sys.stderr.flush()
        except Exception:
            pass

    def _spec(self, name: str) -> ServiceSpec:
        key = str(name or "").strip().lower()
        spec = self._specs.get(key)
        if spec is None:
            raise ServiceError(
                "未知服务：%r" % (name,),
                detail={"name": name, "known": sorted(self._specs)},
            )
        return spec

    def _status_safe(self, name: str) -> Dict[str, Any]:
        try:
            return self.service_status(name)
        except Exception as exc:
            return {"name": name, "running": False, "error": str(exc), "pids": [], "port_open": False}

    def _is_up(self, name: str) -> bool:
        if name not in self._specs:
            return False
        try:
            return bool(self.service_status(name)["running"])
        except Exception:
            return False

    def _match_pids(self, spec: ServiceSpec) -> List[int]:
        """找出属于这个服务的所有进程（壳 + worker）。"""
        patterns: Sequence[str] = spec.patterns or (os.path.basename(spec.target),)
        try:
            return find_pids(patterns, cwd=spec.cwd if spec.match_cwd else None)
        except Exception as exc:
            self._log("扫描 %s 进程失败：%s" % (spec.name, exc))
            return []

    def _probe(self, spec: ServiceSpec, timeout: float = 1.0) -> bool:
        """就绪/存活探测：有 health_url 走 HTTP，否则走 TCP 端口。"""
        if spec.health_url:
            return http_ok(spec.health_url, timeout=max(0.5, float(timeout)))
        return port_open(HOST, spec.port, timeout=min(0.5, max(0.05, float(timeout))))

    def _ready(self, name: str) -> bool:
        """服务是否真的"可用"（端口 / HTTP 探活通过）。

        与 ``_is_up``（进程存在或端口在听）不同：这里要求能被客户端真正用上。
        判"是否已经处于目标模式"时用它，避免"进程还在但服务已死"被误判成无需切换。
        """
        if name not in self._specs:
            return False
        if self._dry_run:
            return bool(self._dry_running.get(name))
        try:
            return self._probe(self._specs[name], 1.0)
        except Exception:
            return False

    # ------------------------------------------------------------------ #
    # 编排锁
    # ------------------------------------------------------------------ #
    @contextlib.contextmanager
    def _guard(self, force_lock: bool = False, timeout: Optional[float] = None) -> Iterator[None]:
        """拿编排锁。

        ``force_lock=True`` 给"已经持锁的同一线程"用（RLock 可重入），
        例如 ``comfy_session`` 内部的 ensure_mode/start/stop 调用链。
        其余情况带 ``lock_wait`` 超时，拿不到就抛 ``ServiceBusy``（server → 409）。
        """
        wait = self._lock_wait if timeout is None else float(timeout)
        if force_lock:
            acquired = bool(self._lock.acquire())
        elif wait <= 0:
            acquired = self._lock.acquire(blocking=False)
        else:
            acquired = self._lock.acquire(timeout=wait)
        if not acquired:
            raise ServiceBusy(
                "编排锁被占用：有生成任务正在进行",
                detail={"busy": True, "lock_wait": wait},
            )
        try:
            yield
        finally:
            try:
                self._lock.release()
            except RuntimeError:
                pass

    # ------------------------------------------------------------------ #
    # 信号 / 回收
    # ------------------------------------------------------------------ #
    def _send(self, pid: int, sig: int) -> bool:
        """向单个进程发信号；进程已走 / 无权限都不算异常。"""
        if not pid or int(pid) <= 1:
            return False
        try:
            os.kill(int(pid), sig)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            self._log("无权向 pid=%s 发送信号 %s" % (pid, sig))
            return False
        except OSError as exc:
            self._log("向 pid=%s 发送信号 %s 失败：%s" % (pid, sig, exc))
            return False

    def _send_group(self, pgid: int, sig: int) -> bool:
        """向整个进程组发信号（我们 spawn 的进程自成一个 session，pgid == 子进程 pid）。

        安全阀：绝不向自己的进程组发信号，否则会把自己的 server 一起带走。
        """
        if not pgid or int(pgid) <= 1:
            return False
        try:
            if int(pgid) == os.getpgid(0):
                self._log("拒绝向自身进程组 %s 发送信号" % pgid)
                return False
        except OSError:
            return False
        try:
            os.killpg(int(pgid), sig)
            self._log("向进程组 %s 发送信号 %s" % (pgid, sig))
            return True
        except ProcessLookupError:
            return False
        except (PermissionError, OSError) as exc:
            self._log("向进程组 %s 发送信号 %s 失败：%s" % (pgid, sig, exc))
            return False

    def _wait_gone_list(self, pids: Sequence[int], timeout: float) -> bool:
        """轮询直到这批 pid 全部消失（僵尸算已消失）。"""
        watch = [int(p) for p in pids if p]
        if not watch:
            return True
        return wait_until(
            lambda: not any(pid_alive(p) for p in watch),
            timeout,
            interval=0.25,
        )

    def _stop_processes(self, spec: ServiceSpec, wait: bool = True) -> Dict[str, Any]:
        """真正的停止动作：先杀 while-true 壳，再 SIGTERM worker，最后兜底 SIGKILL。

        顺序是刻意的：``bash run-ttsQwenQ8.sh`` 里的 ``while true`` 会不停重启
        ``tts-server``，先杀 worker 等于给它一次复活机会；只有把壳 ``SIGKILL``
        掉，"再生源"才消失。杀壳后必须**重新扫描**（新出生的 worker 不在旧列表里），
        然后才 SIGTERM，最后再扫描一次确认没有漏网。

        返回结构化 detail（哪些 pid 被怎么处理、端口是否已关），不抛异常；
        是否失败由调用方根据 ``leftover`` 判断。
        """
        detail: Dict[str, Any] = {
            "wrappers_killed": [],
            "sigterm": [],
            "sigkill": [],
            "rounds": 0,
            "leftover": [],
            "processes_gone": True,
            "port_closed": True,
        }
        if self._dry_run:
            self._log("[dry-run] 跳过对 %s 的真实 kill" % spec.name)
            return detail

        pgid = self._pgids.get(spec.name)

        for rnd in range(1, 4):  # 最多三轮，防止 while-true 的"重生竞态"
            detail["rounds"] = rnd
            pids = self._match_pids(spec)
            if not pids:
                break
            wrappers = [p for p in pids if _pid_is_wrapper(p)]
            # (1) 壳先死：SIGKILL 不留清理窗口，循环立刻终止
            for pid in wrappers:
                if self._send(pid, signal.SIGKILL):
                    detail["wrappers_killed"].append(pid)
            if wrappers:
                self._log("%s：已 SIGKILL 重启壳 %s" % (spec.name, wrappers))
                self._wait_gone_list(wrappers, timeout=2.0)
            # (2) 重新扫描（壳可能在窗口期里拉起了一个新 worker）
            pids = self._match_pids(spec)
            if not pids:
                break
            # (3) worker：SIGTERM 优雅退出（ComfyUI 需要它来释放 CUDA 上下文）
            if pgid:
                self._send_group(pgid, signal.SIGTERM)
            for pid in pids:
                if self._send(pid, signal.SIGTERM):
                    detail["sigterm"].append(pid)
            self._log("%s：已 SIGTERM %s，等待退出" % (spec.name, pids))
            if self._wait_gone_list(pids, timeout=self._stop_timeout):
                break
            # (4) 超时 → SIGKILL 兜底
            pids = self._match_pids(spec) or pids
            if pgid:
                self._send_group(pgid, signal.SIGKILL)
            for pid in pids:
                if self._send(pid, signal.SIGKILL):
                    detail["sigkill"].append(pid)
            self._log("%s：超时，已 SIGKILL %s" % (spec.name, pids))
            if self._wait_gone_list(pids, timeout=5.0):
                break

        # 收尾：再扫一次，确认没有"自动重启"的残留（tts 尤其重要）
        leftover = self._match_pids(spec)
        if leftover:
            self._log("%s：发现残留进程 %s，最后 SIGKILL" % (spec.name, leftover))
            for pid in leftover:
                if self._send(pid, signal.SIGKILL):
                    detail["sigkill"].append(pid)
            self._wait_gone_list(leftover, timeout=5.0)
            leftover = self._match_pids(spec)
        detail["leftover"] = leftover
        detail["processes_gone"] = not leftover

        # 端口必须真的关掉，否则下一次 start 会撞端口
        if wait:
            closed = wait_until(
                lambda: not self._probe(spec, 0.5),
                timeout=min(self._stop_timeout, 15.0),
                interval=0.25,
            )
        else:  # wait=False：只探一次，不陪它耗时间
            closed = not self._probe(spec, 0.5)
        detail["port_closed"] = bool(closed)
        if closed and spec.name in self._procs:
            self._reap(spec.name)
        return detail

    def _reap(self, name: str) -> None:
        """回收自己 spawn 的子进程（非阻塞），避免僵尸堆积。"""
        with self._proc_lock:
            proc = self._procs.get(name)
        if proc is None:
            return
        try:
            code = proc.poll()
        except Exception:
            return
        if code is not None:
            self._exit_codes[name] = code
            self._log("%s 的子进程已退出，returncode=%s" % (name, code))

    def _cleanup_residual(self, spec: ServiceSpec) -> None:
        """启动前的残留清理：端口没人听但进程还在（上次 kill 不彻底）时先收拾干净。"""
        if self._dry_run:
            return
        pids = self._match_pids(spec)
        if not pids:
            return
        self._log("%s：启动前发现残留进程 %s，先清理" % (spec.name, pids))
        detail = self._stop_processes(spec)
        if detail.get("leftover"):
            raise ServiceError(
                "%s 启动前的残留进程清理失败：%s" % (spec.name, detail["leftover"]),
                detail={"status": self._status_safe(spec.name), "detail": detail},
            )

    # ------------------------------------------------------------------ #
    # 启动 / 就绪
    # ------------------------------------------------------------------ #
    def _spawn(self, spec: ServiceSpec) -> Tuple[Optional[int], Optional[int]]:
        """拉起服务进程，返回 (pid, pgid)。

        关键点：

        * ``start_new_session=True`` → 子进程新 session、``pgid == pid``，
          停止时可以用 ``killpg`` 整组回收，且服务能活过 server 自己；
        * stdout/stderr 一起追加到日志文件，``stdin=DEVNULL`` 让它绝不因读终端而卡住；
        * 环境里补 ``PYTHONUNBUFFERED=1``，否则 python 服务的日志会憋在缓冲区里，
          出问题时 ``log_tail`` 空白一片。
        """
        cmd = _build_cmd(spec)
        log_path = spec.log_path
        log_dir = os.path.dirname(log_path)
        if log_dir:
            try:
                os.makedirs(log_dir, exist_ok=True)
            except OSError as exc:
                raise ServiceError(
                    "创建日志目录失败：%s（%s）" % (log_dir, exc),
                    detail={"name": spec.name, "log_path": log_path},
                ) from exc

        if self._dry_run:
            self._log("[dry-run] spawn：%s（cwd=%s）" % (" ".join(cmd), spec.cwd))
            self._dry_running[spec.name] = True
            return None, None

        env = os.environ.copy()
        env["PYTHONUNBUFFERED"] = "1"
        env.setdefault("PYTHONIOENCODING", "utf-8")
        try:
            os.makedirs(spec.cwd, exist_ok=True)
        except OSError:
            pass
        if not os.path.isdir(spec.cwd):
            raise ServiceError(
                "%s 的工作目录不存在：%s" % (spec.name, spec.cwd),
                detail={"name": spec.name, "cwd": spec.cwd},
            )

        header = "\n===== %s start %s :: %s (cwd=%s) =====\n" % (
            time.strftime("%Y-%m-%d %H:%M:%S"),
            spec.name,
            " ".join(cmd),
            spec.cwd,
        )
        try:
            with open(log_path, "ab", buffering=0) as log_fh:
                log_fh.write(header.encode("utf-8", "replace"))
                proc = subprocess.Popen(  # noqa: S603 - 命令来自受信任的本地配置
                    cmd,
                    cwd=spec.cwd,
                    stdin=subprocess.DEVNULL,
                    stdout=log_fh,
                    stderr=subprocess.STDOUT,
                    env=env,
                    start_new_session=True,
                    close_fds=True,
                )
        except (OSError, ValueError) as exc:
            raise ServiceError(
                "%s 启动失败：%s" % (spec.name, exc),
                detail={"name": spec.name, "cmd": cmd, "cwd": spec.cwd, "log_tail": tail_file(log_path, 40)},
            ) from exc

        try:
            pgid = os.getpgid(proc.pid)
        except OSError:
            pgid = proc.pid
        with self._proc_lock:
            self._procs[spec.name] = proc
            self._pgids[spec.name] = int(pgid)
        self._log("%s 已启动 pid=%s pgid=%s → %s" % (spec.name, proc.pid, pgid, log_path))
        return proc.pid, pgid

    def _wait_ready(self, spec: ServiceSpec, pid: Optional[int] = None) -> bool:
        """等就绪：llm/tts 等端口 listen，comfy 等 ``/system_stats`` 返回 2xx。

        除了"等到就绪"，还要能"快速失败"：如果刚 spawn 的壳自己就退了
        （模型路径不对、缺 .so、显存不够），立刻抛错并附日志尾部，
        不要让调用方白等 600 秒。
        """
        if self._dry_run:
            self._log("[dry-run] %s 跳过就绪等待" % spec.name)
            return True
        timeout = float(spec.start_timeout or 120.0)
        target = spec.health_url or ("%s:%s" % (HOST, spec.port))
        deadline = time.monotonic() + timeout
        while True:
            if self._probe(spec, timeout=2.0 if spec.health_url else 0.5):
                self._log("%s 已就绪（%s）" % (spec.name, target))
                return True
            if pid and not pid_alive(pid):
                raise ServiceError(
                    "%s 启动后立即退出（pid=%s）" % (spec.name, pid),
                    detail={
                        "name": spec.name,
                        "status": self._status_safe(spec.name),
                        "log_tail": tail_file(spec.log_path, 40),
                    },
                )
            if time.monotonic() >= deadline:
                raise ServiceError(
                    "%s 在 %.0fs 内未就绪（%s）" % (spec.name, timeout, target),
                    detail={
                        "name": spec.name,
                        "start_timeout": timeout,
                        "status": self._status_safe(spec.name),
                        "log_tail": tail_file(spec.log_path, 40),
                    },
                )
            time.sleep(0.5)

    # ------------------------------------------------------------------ #
    # 显存
    # ------------------------------------------------------------------ #
    def _free_at_least(self, min_free_mib: int) -> bool:
        info = self.vram()
        if info is None:
            return True
        try:
            return int(info["free_mib"]) >= int(min_free_mib)
        except (KeyError, TypeError, ValueError):
            return True

    def _vram_step(self, min_free_mib: int) -> Dict[str, Any]:
        """等显存释放到阈值以上。

        ``nvidia-smi`` 不可用时**跳过而不是报错**：这个模块的价值是编排，
        不能因为少了监控工具就把整套流程卡死。
        """
        label = "vram_free>=%dMiB" % int(min_free_mib)
        if self._dry_run:
            return {"step": label, "ok": True, "detail": "dry-run 跳过显存等待"}
        info = self.vram()
        if info is None:
            self._log("nvidia-smi 不可用，跳过显存等待")
            return {"step": label, "ok": True, "detail": "nvidia-smi 不可用，跳过显存等待"}
        if int(info.get("free_mib") or 0) >= int(min_free_mib):
            return {"step": label, "ok": True, "detail": info}
        self._log("等待显存释放到 %d MiB 以上（当前 free=%s MiB）" % (min_free_mib, info.get("free_mib")))
        ok = wait_until(
            lambda: self._free_at_least(min_free_mib),
            timeout=self._vram_wait_timeout,
            interval=1.0,
        )
        now = self.vram()
        if not ok:
            raise ServiceError(
                "显存未在 %.0fs 内释放到 %d MiB 以上" % (self._vram_wait_timeout, min_free_mib),
                detail={"step": label, "vram": now, "min_free_mib": int(min_free_mib)},
            )
        return {"step": label, "ok": True, "detail": now}

    # ------------------------------------------------------------------ #
    # 对外：启停
    # ------------------------------------------------------------------ #
    @staticmethod
    def _brief(value: Any) -> Any:
        """把内部返回值压成适合塞进 steps 的小摘要（避免 steps 里塞整个 status）。"""
        if isinstance(value, dict):
            keys = ("ok", "action", "already_running", "already_stopped", "mode", "switched", "simulated")
            out = {k: value[k] for k in keys if k in value}
            if out:
                return out
            return {"keys": sorted(str(k) for k in value)}
        return value

    def _step(self, label: str, action: Callable[[], Any]) -> Dict[str, Any]:
        """执行一个编排步骤并记录结果；失败时把"是哪一步"带进 ServiceError。"""
        try:
            return {"step": label, "ok": True, "detail": self._brief(action())}
        except (ServiceBusy, ServiceError):
            raise
        except Exception as exc:
            raise ServiceError(
                "%s 失败：%s" % (label, exc),
                detail={"step": label, "error": str(exc)},
            ) from exc

    def start_service(self, name: str, *, wait: bool = True, force_lock: bool = False) -> Dict[str, Any]:
        """启动一个服务（幂等）。

        已运行 → ``already_running=True`` 直接返回；否则先做残留清理，
        再 ``Popen`` 拉起，``wait=True`` 时等到端口/HTTP 就绪才返回。
        任何失败都抛 ``ServiceError``，``detail`` 里带 ``status`` 与 ``log_tail``
        （OOM、缺 .so、模型路径错都能从日志尾巴直接看出来）。
        """
        key = str(name or "").strip().lower()
        try:
            with self._guard(force_lock):
                spec = self._spec(key)
                status = self.service_status(spec.name)
                if status["running"]:
                    self._log("%s 已在运行（pids=%s），跳过启动" % (spec.name, status["pids"]))
                    return {
                        "ok": True,
                        "action": "start",
                        "already_running": True,
                        "status": status,
                    }
                # 上次没杀干净时先清场，否则端口冲突 / 双份占显存
                self._cleanup_residual(spec)
                if not self._dry_run and self._probe(spec, 0.5):
                    raise ServiceError(
                        "%s 的端口 %s 仍被其它进程占用，无法启动" % (spec.name, spec.port),
                        detail={"name": spec.name, "port": int(spec.port), "status": self._status_safe(spec.name)},
                    )
                pid, pgid = self._spawn(spec)
                if wait:
                    self._wait_ready(spec, pid)
                status = self.service_status(spec.name)
                self._last_error[spec.name] = None
                self._log("%s 启动完成（running=%s）" % (spec.name, status["running"]))
                return {
                    "ok": True,
                    "action": "start",
                    "already_running": False,
                    "status": status,
                    "pid": pid,
                    "pgid": pgid,
                }
        except ServiceBusy:
            raise
        except ServiceError as exc:
            self._last_error[key] = exc.message
            raise
        except Exception as exc:
            self._last_error[key] = str(exc)
            raise ServiceError(
                "启动 %s 失败：%s" % (key, exc),
                detail={
                    "name": key,
                    "error": str(exc),
                    "status": self._status_safe(key),
                    "log_tail": self.log_tail(key, 40),
                },
            ) from exc

    def stop_service(self, name: str, *, wait: bool = True, force_lock: bool = False) -> Dict[str, Any]:
        """停止一个服务（幂等，且能杀掉 ``while true`` 重启壳）。

        未运行 → ``already_stopped=True``；运行中则走 ``_stop_processes``：
        壳 SIGKILL → worker SIGTERM → 超时 SIGKILL → 再扫描确认 → 等端口关闭。
        """
        key = str(name or "").strip().lower()
        try:
            with self._guard(force_lock):
                spec = self._spec(key)
                if self._dry_run:
                    was = bool(self._dry_running.get(spec.name))
                    self._dry_running[spec.name] = False
                    self._log("[dry-run] stop %s（此前运行=%s）" % (spec.name, was))
                    return {
                        "ok": True,
                        "action": "stop",
                        "already_stopped": not was,
                        "status": self.service_status(spec.name),
                        "simulated": True,
                    }
                status = self.service_status(spec.name)
                if not status["running"]:
                    self._last_error[spec.name] = None
                    self._log("%s 未在运行，无需停止" % spec.name)
                    return {
                        "ok": True,
                        "action": "stop",
                        "already_stopped": True,
                        "status": status,
                    }
                detail = self._stop_processes(spec, wait=wait)
                if detail.get("leftover"):
                    raise ServiceError(
                        "%s 停止失败：仍有残留进程 %s" % (spec.name, detail["leftover"]),
                        detail={
                            "name": spec.name,
                            "detail": detail,
                            "status": self._status_safe(spec.name),
                            "log_tail": tail_file(spec.log_path, 40),
                        },
                    )
                if not detail.get("port_closed"):
                    handled = (detail.get("wrappers_killed")
                               or detail.get("sigterm")
                               or detail.get("sigkill"))
                    if not handled:
                        # 端口听着、但过程扫描一个人都没找到：八成是外部进程占着，
                        # 这里必须报错，否则下一步 start 会静默撞端口 / 双份吃显存。
                        raise ServiceError(
                            "%s 的端口 %s 被无法识别的进程占用（cmdline 与我们记录的特征不符）" % (
                                spec.name, spec.port),
                            detail={
                                "name": spec.name,
                                "port": int(spec.port),
                                "detail": detail,
                                "status": self._status_safe(spec.name),
                            },
                        )
                    self._log("%s 的端口 %s 在停止后仍未关闭（可能有外部进程占用）" % (spec.name, spec.port))
                status = self.service_status(spec.name)
                with self._proc_lock:
                    self._pgids.pop(spec.name, None)
                self._last_error[spec.name] = None
                self._log("%s 已停止（processes_gone=%s port_closed=%s）" % (
                    spec.name, detail.get("processes_gone"), detail.get("port_closed")))
                return {
                    "ok": True,
                    "action": "stop",
                    "already_stopped": False,
                    "status": status,
                    "detail": detail,
                }
        except ServiceBusy:
            raise
        except ServiceError as exc:
            self._last_error[key] = exc.message
            raise
        except Exception as exc:
            self._last_error[key] = str(exc)
            raise ServiceError(
                "停止 %s 失败：%s" % (key, exc),
                detail={
                    "name": key,
                    "error": str(exc),
                    "status": self._status_safe(key),
                    "log_tail": self.log_tail(key, 40),
                },
            ) from exc

    # ------------------------------------------------------------------ #
    # 对外：模式切换
    # ------------------------------------------------------------------ #
    def ensure_mode(self, mode: str, *, force_lock: bool = False) -> Dict[str, Any]:
        """把机器切到目标模式（幂等）。

        * ``mode="comfy"``：停 llm → 停 tts → 等显存释放 → 起 comfy → 等 ``/system_stats``；
        * ``mode="llm"``  ：停 comfy → 等显存释放 → 起 llm → 起 tts。

        返回 ``{"ok","mode","switched","steps":[...],"mode_after"}``；
        失败抛 ``ServiceError``，``detail["steps"]`` 里保留已走完的步骤，
        便于 server 直接把"卡在哪一步 + 日志尾巴"回给前端。
        """
        try:
            with self._guard(force_lock):
                return self._ensure_mode_locked(mode)
        except ServiceBusy:
            raise
        except ServiceError:
            raise
        except Exception as exc:
            raise ServiceError(
                "切换模式 %r 失败：%s" % (mode, exc),
                detail={"mode": mode, "error": str(exc)},
            ) from exc

    def _ensure_mode_locked(self, mode: str) -> Dict[str, Any]:
        """ensure_mode 的实际动作；调用前必须已持有编排锁。"""
        want = str(mode or "").strip().lower()
        if want not in (SERVICE_COMFY, SERVICE_LLM):
            raise ServiceError(
                "未知模式：%r（只支持 comfy / llm）" % (mode,),
                detail={"mode": mode, "known": [SERVICE_COMFY, SERVICE_LLM]},
            )
        previous = self.current_mode()
        steps: List[Dict[str, Any]] = []
        try:
            if want == SERVICE_COMFY:
                if (self._ready(SERVICE_COMFY)
                        and not self._is_up(SERVICE_LLM)
                        and not self._is_up(SERVICE_TTS)):
                    self._log("当前已是 comfy 模式，无需切换")
                    return {
                        "ok": True,
                        "mode": want,
                        "switched": False,
                        "previous_mode": previous,
                        "steps": steps,
                        "status": self._status_safe(SERVICE_COMFY),
                    }
                steps.append(self._step(
                    "stop:%s" % SERVICE_LLM,
                    lambda: self.stop_service(SERVICE_LLM, wait=True, force_lock=True)))
                steps.append(self._step(
                    "stop:%s" % SERVICE_TTS,
                    lambda: self.stop_service(SERVICE_TTS, wait=True, force_lock=True)))
                steps.append(self._vram_step(self._comfy_min_free_mib))
                steps.append(self._step(
                    "start:%s" % SERVICE_COMFY,
                    lambda: self.start_service(SERVICE_COMFY, wait=True, force_lock=True)))
            else:
                if (self._ready(SERVICE_LLM)
                        and self._ready(SERVICE_TTS)
                        and not self._is_up(SERVICE_COMFY)):
                    self._log("当前已是 llm 模式，无需切换")
                    return {
                        "ok": True,
                        "mode": want,
                        "switched": False,
                        "previous_mode": previous,
                        "steps": steps,
                        "status": self._status_safe(SERVICE_LLM),
                    }
                steps.append(self._step(
                    "stop:%s" % SERVICE_COMFY,
                    lambda: self.stop_service(SERVICE_COMFY, wait=True, force_lock=True)))
                steps.append(self._vram_step(self._llm_min_free_mib))
                steps.append(self._step(
                    "start:%s" % SERVICE_LLM,
                    lambda: self.start_service(SERVICE_LLM, wait=True, force_lock=True)))
                steps.append(self._step(
                    "start:%s" % SERVICE_TTS,
                    lambda: self.start_service(SERVICE_TTS, wait=True, force_lock=True)))
        except BaseException as exc:
            if isinstance(exc, ServiceError):
                failed_step = exc.detail.get("step")
                if failed_step:
                    steps.append({"step": failed_step, "ok": False, "detail": exc.detail})
                exc.detail.setdefault("mode", want)
                exc.detail.setdefault("previous_mode", previous)
                exc.detail.setdefault("steps", steps)
            raise
        result = {
            "ok": True,
            "mode": want,
            "switched": True,
            "previous_mode": previous,
            "steps": steps,
            "mode_after": self.current_mode(),
            "status": self._status_safe(want),
        }
        self._log("模式切换完成：%s → %s" % (previous, result["mode_after"]))
        return result

    # ------------------------------------------------------------------ #
    # 对外：生成会话
    # ------------------------------------------------------------------ #
    @contextlib.contextmanager
    def comfy_session(
        self,
        *,
        restore: Optional[str] = "llm",
        restore_on_error: bool = True,
        lock_timeout: Optional[float] = None,
    ) -> Iterator[Dict[str, Any]]:
        """生成用的独占窗口：进来自动切到 comfy，出去自动切回 ``restore``。

        用法::

            with mgr.comfy_session(restore="llm") as info:
                ...  # 这里可以放心用 ComfyUI 的 22G 显存

        * 进入时先拿编排锁（拿不到抛 ``ServiceBusy`` → 409），再把 ``busy`` 置 True；
        * ``finally`` 里无论成功、失败还是抛异常都会 ``ensure_mode(restore)``，
          恢复失败只记日志不掩盖原始异常（restore 失败的机器需要人工看一眼）；
        * ``restore=None`` 表示"用完就停在 comfy 模式"，只释放锁。
        """
        timeout = self._lock_wait if lock_timeout is None else float(lock_timeout)
        acquired = self._lock.acquire(timeout=timeout) if timeout > 0 else self._lock.acquire(blocking=False)
        if not acquired:
            raise ServiceBusy(
                "有其它生成/切换任务正在进行，请稍后重试",
                detail={"busy": True, "lock_wait": timeout},
            )
        previous_busy = self._busy
        self._busy = True
        failed = False
        try:
            info = self._ensure_mode_locked(SERVICE_COMFY)
            yield info
        except BaseException:
            failed = True
            raise
        finally:
            self._busy = previous_busy
            try:
                if restore and (restore_on_error or not failed):
                    try:
                        self._ensure_mode_locked(restore)
                    except Exception as exc:
                        self._log("恢复 %s 模式失败：%s" % (restore, exc))
            finally:
                try:
                    self._lock.release()
                except RuntimeError:
                    pass

    # ------------------------------------------------------------------ #
    # 收尾
    # ------------------------------------------------------------------ #
    def shutdown(self, *, stop_all: bool = False) -> None:
        """server 退出时调用。

        默认 ``stop_all=False``：**故意不停服务**——LLM/TTS 是常驻业务，
        重启 server 不应该把模型重新加载一遍（FUSE 盘上要几十秒）。
        需要彻底停机时显式传 ``stop_all=True``。
        """
        self._busy = False
        if not stop_all:
            self._log("shutdown(stop_all=False)：服务继续在后台运行，仅释放编排状态")
            return
        try:
            with self._guard(False, timeout=max(self._lock_wait, 5.0)):
                for name in SERVICE_ORDER:
                    if name not in self._specs:
                        continue
                    try:
                        self.stop_service(name, wait=True, force_lock=True)
                    except Exception as exc:
                        self._log("shutdown：停止 %s 失败：%s" % (name, exc))
        except ServiceBusy:
            self._log("shutdown：编排锁被占用（可能有生成任务），跳过停止")
        except Exception as exc:
            self._log("shutdown 异常：%s" % exc)


# --------------------------------------------------------------------------- #
# 自测：python3 service_manager.py --self-test
# --------------------------------------------------------------------------- #
def run_self_test(argv: Optional[List[str]] = None) -> int:
    """只读 / 纯函数 / dry-run 自检。

    **不启停任何真实服务**：只读 /proc、探端口、探 HTTP、读 nvidia-smi，
    其余流程全部走 ``dry_run=True`` 的内存模拟。
    """
    parser = argparse.ArgumentParser(
        prog="service_manager.py",
        description="GPU 资源互斥编排器（--self-test 不做任何危险操作）",
    )
    parser.add_argument("--self-test", action="store_true", help="跑只读/纯函数/dry-run 自检")
    parser.add_argument("--project-dir", default=PROJECT_DIR)
    parser.add_argument("--logs-dir", default=None, help="日志目录（自检默认用临时目录，不脏化仓库）")
    parser.add_argument("--comfy-dir", default=COMFY_DIR)
    parser.add_argument("--comfy-port", type=int, default=8188)
    parser.add_argument("--llm-port", type=int, default=6008)
    parser.add_argument("--tts-port", type=int, default=9080)
    args = parser.parse_args(argv)

    if not args.self_test:
        parser.print_help()
        return 2

    started = time.time()
    results: List[Tuple[str, bool, str]] = []
    state: Dict[str, Any] = {}

    def check(label: str, fn: Callable[[], Tuple[bool, str]]) -> None:
        try:
            ok, info = fn()
        except Exception as exc:
            ok, info = False, "异常 %s: %s" % (type(exc).__name__, exc)
        results.append((label, bool(ok), info))
        print("[%s] %s" % ("PASS" if ok else "FAIL", label))
        for line in str(info).splitlines() or [""]:
            print("       " + line)
        sys.stdout.flush()

    print("=" * 78)
    print("service_manager.py 自检（只读 / 纯函数 / dry-run，不启停任何真实服务）")
    print("=" * 78)
    sys.stdout.flush()

    # 1) 显存解析
    def _c_vram() -> Tuple[bool, str]:
        info = nvidia_vram()
        if info is None:
            return False, "nvidia-smi 不可用，nvidia_vram() 返回 None"
        ok = (
            isinstance(info.get("total_mib"), int)
            and info["total_mib"] > 0
            and info.get("used_mib") is not None
            and info.get("free_mib") is not None
        )
        return bool(ok), "total=%s MiB, used=%s MiB, free=%s MiB" % (
            info.get("total_mib"), info.get("used_mib"), info.get("free_mib"))

    # 2) 绝对路径 pattern 命中现有 ComfyUI
    comfy_main = os.path.join(args.comfy_dir, "main.py")

    def _c_find_abs() -> Tuple[bool, str]:
        pids = find_pids([comfy_main])
        state["pids_abs"] = pids
        cmdlines = ["pid=%s cwd=%s cmd=%s" % (p, read_cwd(p), " ".join(read_cmdline(p))) for p in pids]
        return bool(pids), "find_pids([%r]) -> %s\n%s" % (comfy_main, pids, "\n".join(cmdlines))

    # 3) cwd 过滤 + 相对名命中，结果应与上一条一致
    def _c_find_cwd() -> Tuple[bool, str]:
        pids = find_pids(["main.py"], cwd=args.comfy_dir)
        state["pids_cwd"] = pids
        same = sorted(pids) == sorted(state.get("pids_abs") or [])
        return bool(pids) and same, "find_pids(['main.py'], cwd=%r) -> %s（与上条一致=%s）" % (
            args.comfy_dir, pids, same)

    # 4) ComfyUI 端口
    def _c_port_comfy() -> Tuple[bool, str]:
        return port_open(HOST, args.comfy_port), "port_open('%s', %d) -> %s" % (
            HOST, args.comfy_port, port_open(HOST, args.comfy_port))

    # 5a) LLM 端口应为关（未启动）
    def _c_port_llm() -> Tuple[bool, str]:
        opened = port_open(HOST, args.llm_port)
        return (not opened), "port_open('%s', %d) -> %s（期望 False）" % (HOST, args.llm_port, opened)

    # 5b) ComfyUI HTTP 探活
    def _c_http_comfy() -> Tuple[bool, str]:
        url = "http://%s:%d/system_stats" % (HOST, args.comfy_port)
        return http_ok(url), "http_ok(%r) -> %s" % (url, http_ok(url))

    # 6) default_specs
    def _c_specs() -> Tuple[bool, str]:
        logs = args.logs_dir or state.get("logs_dir") or tempfile.mkdtemp(prefix="svcmgr-selftest-")
        state["logs_dir"] = logs
        specs = default_specs(
            args.project_dir,
            logs,
            comfy_dir=args.comfy_dir,
            comfy_port=args.comfy_port,
            llm_port=args.llm_port,
            tts_port=args.tts_port,
        )
        state["specs"] = specs
        lines: List[str] = []
        for name in SERVICE_ORDER:
            spec = specs[name]
            lines.append("%-5s kind=%-6s port=%-5d cwd=%s" % (spec.name, spec.kind, spec.port, spec.cwd))
            lines.append("      target = %s" % spec.target)
            lines.append("      log    = %s (timeout=%.0fs, vram=%s)" % (
                spec.log_path, spec.start_timeout, spec.vram_hint or "-"))
            if spec.health_url:
                lines.append("      health = %s" % spec.health_url)
        ok = (
            len(specs) == 3
            and specs[SERVICE_LLM].port == args.llm_port
            and specs[SERVICE_TTS].port == args.tts_port
            and specs[SERVICE_COMFY].port == args.comfy_port
            and os.path.isdir(specs[SERVICE_COMFY].cwd)
            and os.path.isfile(specs[SERVICE_LLM].target)
            and os.path.isfile(specs[SERVICE_TTS].target)
            and os.path.isdir(logs)
        )
        return ok, "\n".join(lines)

    # 7) dry-run 全流程（不碰真实进程）
    def _c_dry_run() -> Tuple[bool, str]:
        specs = state.get("specs")
        if not specs:
            return False, "default_specs 未生成"
        notes: List[str] = []
        ok = True
        collected: List[str] = []
        mgr = ServiceManager(specs, dry_run=True, logger=collected.append)

        snap = mgr.snapshot()
        ok = ok and bool(snap["ok"]) and snap["mode"] == "idle"
        notes.append("snapshot          : ok=%s mode=%s busy=%s services=%d" % (
            snap["ok"], snap["mode"], snap["busy"], len(snap["services"])))

        r = mgr.start_service(SERVICE_LLM)
        ok = ok and bool(r["ok"]) and r["already_running"] is False
        notes.append("start llm         : ok=%s already_running=%s mode=%s" % (
            r["ok"], r["already_running"], mgr.current_mode()))

        r = mgr.start_service(SERVICE_LLM)
        ok = ok and bool(r["already_running"])
        notes.append("start llm (again) : already_running=%s（幂等）" % r["already_running"])

        r = mgr.stop_service(SERVICE_LLM)
        ok = ok and bool(r["ok"]) and r["already_stopped"] is False
        notes.append("stop  llm         : ok=%s already_stopped=%s mode=%s" % (
            r["ok"], r["already_stopped"], mgr.current_mode()))

        r = mgr.stop_service(SERVICE_LLM)
        ok = ok and bool(r["already_stopped"])
        notes.append("stop  llm (again) : already_stopped=%s（幂等）" % r["already_stopped"])

        r = mgr.ensure_mode("comfy")
        ok = ok and bool(r["ok"]) and mgr.current_mode() == "comfy"
        notes.append("ensure_mode comfy : ok=%s switched=%s steps=%s mode=%s" % (
            r["ok"], r["switched"], [s["step"] for s in r["steps"]], mgr.current_mode()))

        r = mgr.ensure_mode("comfy")
        ok = ok and bool(r["ok"]) and r["switched"] is False
        notes.append("ensure_mode comfy : switched=%s（幂等）" % r["switched"])

        r = mgr.ensure_mode("llm")
        ok = ok and bool(r["ok"]) and mgr.current_mode() == "llm"
        notes.append("ensure_mode llm   : ok=%s switched=%s steps=%s mode=%s" % (
            r["ok"], r["switched"], [s["step"] for s in r["steps"]], mgr.current_mode()))

        with mgr.comfy_session(restore="llm") as info:
            ok = ok and bool(info["ok"]) and mgr.busy() and mgr.current_mode() == "comfy"
            notes.append("comfy_session 内  : ok=%s busy=%s mode=%s" % (
                info["ok"], mgr.busy(), mgr.current_mode()))
        ok = ok and (not mgr.busy()) and mgr.current_mode() == "llm"
        notes.append("comfy_session 后  : busy=%s mode=%s（自动恢复）" % (
            mgr.busy(), mgr.current_mode()))

        # 编排锁互斥：别的线程持锁时 start/stop 必须给 ServiceBusy（server → 409）
        holder = threading.Thread(target=_hold_lock_briefly, args=(mgr, state), daemon=True)
        holder.start()
        wait_until(lambda: bool(state.get("holding")), 2.0, interval=0.05)
        busy_raised = False
        try:
            mgr.start_service(SERVICE_COMFY)
        except ServiceBusy:
            busy_raised = True
        holder.join(timeout=3.0)
        state["holding"] = False
        ok = ok and busy_raised
        notes.append("锁互斥            : ServiceBusy=%s（server 应映射 409）" % busy_raised)
        notes.append("编排日志          : 共 %d 行，末 3 行：" % len(collected))
        for line in collected[-3:]:
            notes.append("  " + line)
        return bool(ok), "\n".join(notes)

    # 8) tail_file：文件不存在返回空串；正常文件能取到最后 N 行
    def _c_tail() -> Tuple[bool, str]:
        missing = os.path.join(tempfile.gettempdir(), "svcmgr-no-such-file-%d.log" % os.getpid())
        empty = tail_file(missing, 20)
        path = os.path.join(state.get("logs_dir") or tempfile.gettempdir(), "selftest-tail.log")
        with open(path, "w", encoding="utf-8") as fh:
            for i in range(1, 101):
                fh.write("line-%03d\n" % i)
        got = tail_file(path, 3).splitlines()
        ok = empty == "" and got == ["line-098", "line-099", "line-100"]
        return ok, "不存在文件 -> %r\ntail(3 行) -> %s" % (empty, got)

    check("1) nvidia_vram() 解析", _c_vram)
    check("2) find_pids([comfy/main.py]) 命中现有 ComfyUI", _c_find_abs)
    check("3) find_pids(['main.py'], cwd=comfy_dir) 一致", _c_find_cwd)
    check("4) port_open(8188) 为 True", _c_port_comfy)
    check("5) port_open(6008) 为 False", _c_port_llm)
    check("6) http_ok(/system_stats) 为 True", _c_http_comfy)
    check("7) default_specs() 三个 spec", _c_specs)
    check("8) dry-run 编排全流程", _c_dry_run)
    check("9) tail_file 边界", _c_tail)

    failed = [item for item in results if not item[1]]
    print("-" * 78)
    print("自检结果：%d/%d 通过，耗时 %.2fs" % (
        len(results) - len(failed), len(results), time.time() - started))
    if state.get("logs_dir"):
        print("（本次自检使用的日志目录：%s）" % state["logs_dir"])
    if failed:
        print("失败项：")
        for label, _, _ in failed:
            print("  - " + label)
        print("提示：第 2/3/4 项依赖本机真实运行的 ComfyUI（8188）；")
        print("      如果 ComfyUI 没启动，这几项 FAIL 属于预期，不是代码缺陷。")
        return 1
    print("全部通过 ✅")
    return 0


def _hold_lock_briefly(mgr: ServiceManager, state: Dict[str, Any]) -> None:
    """自测辅助：占住编排锁 0.6s，验证另一个入口会拿到 ServiceBusy。"""
    try:
        with mgr._lock:  # noqa: SLF001 - 自测就是要验证锁行为
            state["holding"] = True
            time.sleep(0.6)
    finally:
        state["holding"] = False


if __name__ == "__main__":
    sys.exit(run_self_test(sys.argv[1:]))

