#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ComfyUI 纯标准库 HTTP 客户端。

只依赖 Python 3.10 标准库（urllib / json / mimetypes / os / threading），
不依赖 requests，也不直接读写 ComfyUI 安装目录里的文件——图片上传与结果下载
全部走 ComfyUI 自己的 HTTP 接口。

典型用法::

    from comfy_client import ComfyClient

    cli = ComfyClient("http://127.0.0.1:8188")
    print(cli.system_stats()["system"]["comfyui_version"])
    name = cli.upload_image("./demo.png")["name"]
    prompt_id = cli.queue_prompt(api_prompt)
    entry = cli.wait(prompt_id, timeout=1800)
    files = cli.download_outputs(entry, "./outputs")
"""

from __future__ import annotations

import json
import mimetypes
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple

__all__ = [
    "ComfyError",
    "ComfyConnectionError",
    "ComfyTimeout",
    "ComfyExecutionError",
    "ComfyClient",
]

DEFAULT_BASE_URL = "http://127.0.0.1:8188"


# --------------------------------------------------------------------------- #
# 异常
# --------------------------------------------------------------------------- #
class ComfyError(Exception):
    """所有 ComfyUI 相关错误的基类。"""

    def __init__(
        self,
        message: str,
        status: Optional[int] = None,
        detail: Any = None,
        payload: Any = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.detail = detail
        self.payload = payload

    def __str__(self) -> str:  # pragma: no cover - 仅用于展示
        if self.status is not None:
            return f"{self.message} (HTTP {self.status})"
        return self.message


class ComfyConnectionError(ComfyError):
    """连不上 ComfyUI 服务（网络层）。"""


class ComfyTimeout(ComfyError):
    """等待任务完成超时。"""


class ComfyExecutionError(ComfyError):
    """ComfyUI 执行期报错（status_str == "error" / 校验失败等）。"""


# --------------------------------------------------------------------------- #
# 内部工具
# --------------------------------------------------------------------------- #
def _encode_multipart(
    fields: Dict[str, str],
    files: Iterable[Tuple[str, str, bytes, Optional[str]]],
) -> Tuple[bytes, str]:
    """手工拼 multipart/form-data body（避免第三方依赖）。

    files 的每一项为 (字段名, 文件名, 内容字节, content-type)。
    """
    boundary = "----ComfyClientBoundary" + uuid.uuid4().hex
    buf = bytearray()
    for key, value in fields.items():
        buf += f"--{boundary}\r\n".encode("utf-8")
        buf += (
            'Content-Disposition: form-data; name="%s"\r\n\r\n' % key
        ).encode("utf-8")
        buf += str(value).encode("utf-8")
        buf += b"\r\n"
    for field_name, filename, content, content_type in files:
        ctype = content_type or "application/octet-stream"
        # 文件名里的引号需要转义，中文文件名在 ComfyUI 侧会被规范化
        safe_name = filename.replace('"', "_")
        buf += f"--{boundary}\r\n".encode("utf-8")
        buf += (
            'Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
            % (field_name, safe_name)
        ).encode("utf-8")
        buf += f"Content-Type: {ctype}\r\n\r\n".encode("utf-8")
        buf += content
        buf += b"\r\n"
    buf += f"--{boundary}--\r\n".encode("utf-8")
    return bytes(buf), f"multipart/form-data; boundary={boundary}"


def _guess_content_type(filename: str) -> str:
    ctype, _ = mimetypes.guess_type(filename)
    return ctype or "application/octet-stream"


def _unique_path(dest_dir: str, filename: str) -> str:
    """避免覆盖同名文件：存在时追加 _1 / _2 ..."""
    base, ext = os.path.splitext(filename)
    candidate = os.path.join(dest_dir, filename)
    index = 1
    while os.path.exists(candidate):
        candidate = os.path.join(dest_dir, f"{base}_{index}{ext}")
        index += 1
    return candidate


# --------------------------------------------------------------------------- #
# 客户端
# --------------------------------------------------------------------------- #
class ComfyClient:
    """ComfyUI HTTP API 的最小封装。"""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        client_id: Optional[str] = None,
        timeout: float = 60.0,
    ) -> None:
        self.base_url = (base_url or DEFAULT_BASE_URL).rstrip("/")
        self.client_id = client_id or uuid.uuid4().hex
        self.timeout = timeout
        self._object_info_cache: Dict[str, Any] = {}
        self._object_info_all: Optional[Dict[str, Any]] = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ #
    # 底层请求
    # ------------------------------------------------------------------ #
    def url(self, path: str) -> str:
        if not path.startswith("/"):
            path = "/" + path
        return self.base_url + path

    def _raw_request(
        self,
        method: str,
        path: str,
        data: Optional[bytes] = None,
        headers: Optional[Dict[str, str]] = None,
        timeout: Optional[float] = None,
    ) -> Tuple[int, bytes]:
        url = self.url(path)
        req = urllib.request.Request(url, data=data, method=method)
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(
                req, timeout=timeout or self.timeout
            ) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as exc:  # 4xx / 5xx 仍然返回 body
            body = b""
            try:
                body = exc.read()
            except Exception:  # pragma: no cover - 极端情况
                pass
            return exc.code, body
        except urllib.error.URLError as exc:
            raise ComfyConnectionError(
                f"无法连接 ComfyUI ({self.base_url})：{exc.reason}"
            ) from exc
        except TimeoutError as exc:  # socket timeout
            raise ComfyConnectionError(
                f"连接 ComfyUI ({self.base_url}) 超时：{exc}"
            ) from exc

    def _request_json(
        self,
        method: str,
        path: str,
        payload: Optional[Any] = None,
        data: Optional[bytes] = None,
        headers: Optional[Dict[str, str]] = None,
        timeout: Optional[float] = None,
    ) -> Any:
        hdrs = {"Accept": "application/json"}
        body = data
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json; charset=utf-8"
        hdrs.update(headers or {})
        status, raw = self._raw_request(method, path, body, hdrs, timeout)
        text = raw.decode("utf-8", errors="replace") if raw else ""
        if status >= 400:
            detail: Any = text
            payload_obj: Any = None
            try:
                parsed = json.loads(text)
                payload_obj = parsed
                # ComfyUI 校验失败时是 {"error": {...}, "node_errors": {...}}
                if isinstance(parsed, dict):
                    detail = parsed.get("error") or parsed.get("detail") or parsed
            except Exception:
                pass
            raise ComfyError(
                f"ComfyUI 接口 {path} 返回 HTTP {status}",
                status=status,
                detail=detail,
                payload=payload_obj,
            )
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text

    # ------------------------------------------------------------------ #
    # 基础接口
    # ------------------------------------------------------------------ #
    def system_stats(self) -> Dict[str, Any]:
        """GET /system_stats —— ComfyUI 版本、显存等信息。"""
        return self._request_json("GET", "/system_stats") or {}

    def object_info(self, cls: Optional[str] = None) -> Any:
        """GET /object_info(/{class})，进程内缓存。"""
        if cls:
            with self._lock:
                if cls in self._object_info_cache:
                    return self._object_info_cache[cls]
            data = self._request_json(
                "GET", "/object_info/" + urllib.parse.quote(cls, safe="")
            )
            with self._lock:
                self._object_info_cache[cls] = data
            return data
        with self._lock:
            if self._object_info_all is not None:
                return self._object_info_all
        data = self._request_json("GET", "/object_info")
        with self._lock:
            self._object_info_all = data
        return data

    def class_names(self) -> List[str]:
        """当前 ComfyUI 注册的全部节点类名。"""
        info = self.object_info()
        return sorted(info.keys()) if isinstance(info, dict) else []

    def upload_image(
        self, path: str, overwrite: bool = True, subfolder: str = ""
    ) -> Dict[str, Any]:
        """POST /upload/image —— 上传本地图片到 ComfyUI 的 input/ 目录。

        返回 {"name": ..., "subfolder": ..., "type": ...}。
        """
        if not os.path.isfile(path):
            raise ComfyError(f"待上传的图片不存在：{path}")
        filename = os.path.basename(path)
        with open(path, "rb") as fh:
            content = fh.read()
        fields: Dict[str, str] = {}
        if overwrite:
            fields["overwrite"] = "true"
        if subfolder:
            fields["subfolder"] = subfolder
        body, content_type = _encode_multipart(
            fields, [("image", filename, content, _guess_content_type(filename))]
        )
        result = self._request_json(
            "POST",
            "/upload/image",
            data=body,
            headers={"Content-Type": content_type},
            timeout=max(self.timeout, 120.0),
        )
        if not isinstance(result, dict) or "name" not in result:
            raise ComfyError("上传图片失败：返回结构异常", detail=result)
        return result

    def queue_prompt(self, api_prompt: Dict[str, Any], extra_data: Optional[Dict[str, Any]] = None,
                     front: bool = False) -> str:
        """POST /prompt —— 提交 API 格式工作流，返回 prompt_id。"""
        payload: Dict[str, Any] = {
            "prompt": api_prompt,
            "client_id": self.client_id,
        }
        if extra_data:
            payload["extra_data"] = extra_data
        if front:
            payload["front"] = True
        result = self._request_json("POST", "/prompt", payload=payload)
        if not isinstance(result, dict) or "prompt_id" not in result:
            raise ComfyError("提交任务失败：返回结构异常", detail=result)
        if result.get("node_errors"):
            raise ComfyExecutionError(
                "提交任务时工作流校验失败",
                detail=result.get("node_errors"),
                payload=result,
            )
        return str(result["prompt_id"])

    def history(self, prompt_id: str) -> Dict[str, Any]:
        """GET /history/{prompt_id}。未完成时可能返回 {}。"""
        data = self._request_json(
            "GET", "/history/" + urllib.parse.quote(str(prompt_id), safe="")
        )
        return data if isinstance(data, dict) else {}

    def queue(self) -> Dict[str, Any]:
        """GET /queue —— {"queue_running": [...], "queue_pending": [...]}。"""
        data = self._request_json("GET", "/queue")
        return data if isinstance(data, dict) else {}

    def queue_position(self, prompt_id: str) -> Optional[int]:
        """返回排队位置（0 表示正在执行）；不在队列中返回 None。"""
        data = self.queue()
        for item in data.get("queue_running") or []:
            if isinstance(item, (list, tuple)) and len(item) > 1 and str(item[1]) == str(prompt_id):
                return 0
        for index, item in enumerate(data.get("queue_pending") or []):
            if isinstance(item, (list, tuple)) and len(item) > 1 and str(item[1]) == str(prompt_id):
                return index + 1
        return None

    def cancel(self, prompt_id: str) -> None:
        """POST /queue {"delete": [prompt_id]} —— 删除排队中的任务。"""
        self._request_json("POST", "/queue", payload={"delete": [str(prompt_id)]})

    def interrupt(self) -> None:
        """POST /interrupt —— 中断正在执行的任务。"""
        self._request_json("POST", "/interrupt", payload={})

    # ------------------------------------------------------------------ #
    # 等待完成
    # ------------------------------------------------------------------ #
    @staticmethod
    def _extract_error(entry: Dict[str, Any]) -> str:
        """从 history entry 的 status 中提取可读错误信息。"""
        status = entry.get("status") or {}
        parts: List[str] = []
        for message in status.get("messages") or []:
            # 形如 ["execution_error", {...}] 或 ["execution_interrupted", {...}]
            if not isinstance(message, (list, tuple)) or len(message) < 2:
                continue
            kind, info = message[0], message[1]
            if kind in ("execution_error", "execution_interrupted") and isinstance(info, dict):
                node_id = info.get("node_id")
                node_type = info.get("node_type")
                text = info.get("exception_message") or info.get("exception_type") or kind
                prefix = f"节点 {node_id}({node_type}) " if node_id else ""
                parts.append(f"{prefix}{str(text).strip()}")
        if not parts:
            parts.append(str(status.get("status_str") or "未知错误"))
        return "; ".join(parts)

    def wait(
        self,
        prompt_id: str,
        timeout: Optional[float] = 1800,
        poll_interval: float = 1.0,
        on_progress: Optional[Callable[[Dict[str, Any]], None]] = None,
    ) -> Dict[str, Any]:
        """轮询 /history 直到任务完成，返回该任务的 history entry。

        同时用 /queue 判断任务是否仍在排队/执行；超时抛 ComfyTimeout；
        执行报错抛 ComfyExecutionError（detail 中含节点与异常信息）。
        """
        started = time.time()
        missing_streak = 0
        last_progress: Optional[Dict[str, Any]] = None
        while True:
            entry = self.history(prompt_id).get(prompt_id) if prompt_id else None
            if isinstance(entry, dict):
                status = entry.get("status") or {}
                state = status.get("status_str")
                # 老版本 ComfyUI 可能没有 status，只要 outputs 出现就认为完成
                finished = bool(status.get("completed")) or state == "success"
                if state == "error":
                    raise ComfyExecutionError(
                        "ComfyUI 执行失败：" + self._extract_error(entry),
                        detail=self._extract_error(entry),
                        payload=entry,
                    )
                if finished and (entry.get("outputs") is not None):
                    if not entry.get("outputs") and state != "success":
                        raise ComfyExecutionError(
                            "任务完成但没有输出：" + self._extract_error(entry),
                            detail=entry.get("status"),
                            payload=entry,
                        )
                    return entry
                # 有 history 记录但还没标记完成 —— 继续等
                missing_streak = 0
            else:
                position = self.queue_position(prompt_id)
                progress = {
                    "prompt_id": prompt_id,
                    "elapsed_sec": round(time.time() - started, 1),
                    "queue_position": position,
                }
                if progress != last_progress:
                    last_progress = progress
                    if on_progress:
                        try:
                            on_progress(progress)
                        except Exception:  # 回调异常不影响主流程
                            pass
                if position is None:
                    # 短暂离开队列可能是在写 history，容忍几次
                    missing_streak += 1
                    if missing_streak >= 3:
                        entry2 = self.history(prompt_id).get(prompt_id)
                        if isinstance(entry2, dict):
                            status = entry2.get("status") or {}
                            if status.get("status_str") == "error":
                                raise ComfyExecutionError(
                                    "ComfyUI 执行失败：" + self._extract_error(entry2),
                                    detail=self._extract_error(entry2),
                                    payload=entry2,
                                )
                            return entry2
                        raise ComfyExecutionError(
                            f"任务 {prompt_id} 既不在队列中也没有历史记录（可能被中断或服务重启）"
                        )
                else:
                    missing_streak = 0

            if timeout is not None and (time.time() - started) > timeout:
                raise ComfyTimeout(
                    f"等待任务 {prompt_id} 超时（{timeout} 秒），可用 cancel/interrupt 结束它",
                    detail={"prompt_id": prompt_id, "elapsed_sec": round(time.time() - started, 1)},
                )
            time.sleep(max(0.05, poll_interval))

    # ------------------------------------------------------------------ #
    # 取回结果
    # ------------------------------------------------------------------ #
    def view_url(self, filename: str, subfolder: str = "", file_type: str = "output") -> str:
        query = urllib.parse.urlencode(
            {"filename": filename, "subfolder": subfolder or "", "type": file_type or "output"}
        )
        return self.url("/view?" + query)

    def fetch_output(self, filename: str, subfolder: str = "", file_type: str = "output") -> bytes:
        """GET /view 取回单张图片的原始字节。"""
        path = "/view?" + urllib.parse.urlencode(
            {"filename": filename, "subfolder": subfolder or "", "type": file_type or "output"}
        )
        status, raw = self._raw_request("GET", path, timeout=max(self.timeout, 120.0))
        if status >= 400:
            raise ComfyError(
                f"下载输出失败：{filename} (HTTP {status})",
                status=status,
                detail=raw.decode("utf-8", errors="replace")[:500],
            )
        return raw

    def download_outputs(
        self, history_entry: Dict[str, Any], dest_dir: str
    ) -> List[Dict[str, Any]]:
        """把 history entry 里所有图片下载到 dest_dir。

        返回 [{"filename","subfolder","type","path","url"}]，path 为绝对路径。
        """
        os.makedirs(dest_dir, exist_ok=True)
        results: List[Dict[str, Any]] = []
        outputs = (history_entry or {}).get("outputs") or {}
        for node_id, node_out in outputs.items():
            if not isinstance(node_out, dict):
                continue
            for key in ("images", "gifs"):
                for item in node_out.get(key) or []:
                    if not isinstance(item, dict):
                        continue
                    filename = item.get("filename")
                    if not filename:
                        continue
                    subfolder = item.get("subfolder") or ""
                    file_type = item.get("type") or "output"
                    content = self.fetch_output(filename, subfolder, file_type)
                    target = _unique_path(dest_dir, os.path.basename(filename))
                    with open(target, "wb") as fh:
                        fh.write(content)
                    results.append(
                        {
                            "node_id": str(node_id),
                            "filename": filename,
                            "subfolder": subfolder,
                            "type": file_type,
                            "path": os.path.abspath(target),
                            "url": self.view_url(filename, subfolder, file_type),
                            "size_bytes": len(content),
                        }
                    )
        return results

    # ------------------------------------------------------------------ #
    # 便捷方法
    # ------------------------------------------------------------------ #
    def run(
        self,
        api_prompt: Dict[str, Any],
        dest_dir: str = "./outputs",
        timeout: Optional[float] = 1800,
        poll_interval: float = 1.0,
        on_progress: Optional[Callable[[Dict[str, Any]], None]] = None,
    ) -> Dict[str, Any]:
        """提交 + 等待 + 下载，一步到位。"""
        started = time.time()
        prompt_id = self.queue_prompt(api_prompt)
        entry = self.wait(
            prompt_id,
            timeout=timeout,
            poll_interval=poll_interval,
            on_progress=on_progress,
        )
        images = self.download_outputs(entry, dest_dir)
        return {
            "prompt_id": prompt_id,
            "elapsed_sec": round(time.time() - started, 2),
            "images": images,
            "history": entry,
        }

    def __repr__(self) -> str:  # pragma: no cover
        return f"ComfyClient(base_url={self.base_url!r}, client_id={self.client_id!r})"
