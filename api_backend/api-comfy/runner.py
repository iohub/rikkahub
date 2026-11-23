#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Qwen-Image-Edit 业务封装：加载模板 → 改参数 → 提交 → 落盘。

设计要点：

* **不硬编码节点 id**：通过 KSampler 的 ``positive`` / ``negative`` 输入溯源到
  正、负 TextEncodeQwenImageEditPlus 节点；尺寸入口则按类名
  （EmptyLatentImage / CR SDXL Aspect Ratio）查找。
* 参考图：``images`` 逐张填 ``image1/image2/image3``，模板里没有对应
  LoadImage 时会**动态补节点**。
* ``images=[]`` + ``no_reference=True`` → 删除 image1/2/3 连线，退化为纯文生图。
"""

from __future__ import annotations

import base64
import copy
import json
import os
import random
import tempfile
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

try:
    from comfy_client import ComfyClient, ComfyError
except ImportError:  # pragma: no cover
    ComfyClient = None  # type: ignore
    ComfyError = Exception  # type: ignore

__all__ = [
    "DEFAULT_TEMPLATE",
    "PROMPT_PLACEHOLDER",
    "load_template",
    "find_sampler_node",
    "find_encoder_nodes",
    "parse_size",
    "format_size",
    "build_prompt",
    "generate",
]

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TEMPLATE = os.path.join(PROJECT_DIR, "workflows", "qwen_image_edit_api.json")
PROMPT_PLACEHOLDER = "__PROMPT__"
DEFAULT_BASE_URL = "http://127.0.0.1:8188"

SAMPLER_CLASSES = ("KSampler", "KSamplerAdvanced", "KSampler (Efficient)")
LATENT_CLASSES = ("EmptyLatentImage", "EmptySD3LatentImage", "EmptyHiResLatentImage")
SAVE_CLASSES = ("SaveImage", "Image Save", "SaveImageWebsocket")
SIZE_WIDGET_CLASSES = ("CR SDXL Aspect Ratio", "CR Aspect Ratio")
MAX_IMAGES = 3


# --------------------------------------------------------------------------- #
# 模板读写
# --------------------------------------------------------------------------- #
def load_template(path: Optional[str] = None) -> Dict[str, Any]:
    """读取 API 格式模板（默认 workflows/qwen_image_edit_api.json）。"""
    target = path or DEFAULT_TEMPLATE
    if not os.path.isfile(target):
        raise FileNotFoundError(f"模板文件不存在：{target}")
    with open(target, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise ValueError(f"模板不是 JSON 对象：{target}")
    # 过滤非节点键（比如误留的注释字段），避免 /prompt 校验失败
    cleaned: Dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, dict) and "class_type" in value:
            cleaned[str(key)] = value
    if not cleaned:
        raise ValueError(f"模板中没有任何有效节点：{target}")
    return cleaned


def _next_node_id(template: Dict[str, Any]) -> str:
    """分配一个模板内未被占用的节点 id（数字键递增）。"""
    used = set()
    for key in template:
        try:
            used.add(int(str(key)))
        except (TypeError, ValueError):
            continue
    candidate = (max(used) if used else 0) + 1
    while str(candidate) in template:
        candidate += 1
    return str(candidate)


def _ref_id(value: Any) -> Optional[str]:
    """从 ``["3", 0]`` 这类引用里取出节点 id。"""
    if isinstance(value, (list, tuple)) and len(value) >= 2:
        return str(value[0])
    return None


# --------------------------------------------------------------------------- #
# 节点定位
# --------------------------------------------------------------------------- #
def find_sampler_node(template: Dict[str, Any]) -> Tuple[str, Dict[str, Any]]:
    """定位采样节点（KSampler / KSamplerAdvanced ...）。"""
    for cls in SAMPLER_CLASSES:
        for node_id, node in template.items():
            if node.get("class_type") == cls:
                return node_id, node
    # 兜底：任何同时带 positive/negative 输入的节点
    for node_id, node in template.items():
        inputs = node.get("inputs") or {}
        if "positive" in inputs and "negative" in inputs:
            return node_id, node
    raise ValueError("模板中找不到采样节点（KSampler/KSamplerAdvanced）")


def _trace_encoder(
    template: Dict[str, Any], start_id: Optional[str], want_prompt: bool, depth: int = 0
) -> Optional[str]:
    """从 start_id 沿着输入向上找第一个带 prompt 输入的编码节点。"""
    if not start_id or depth > 8:
        return None
    node = template.get(start_id)
    if not isinstance(node, dict):
        return None
    cls = str(node.get("class_type") or "")
    inputs = node.get("inputs") or {}
    if "TextEncode" in cls and (not want_prompt or isinstance(inputs.get("prompt"), str)):
        return start_id
    if "TextEncode" in cls:
        return start_id
    for value in inputs.values():
        upstream = _ref_id(value)
        if upstream and upstream != start_id:
            found = _trace_encoder(template, upstream, want_prompt, depth + 1)
            if found:
                return found
    return None


def find_encoder_nodes(
    template: Dict[str, Any], sampler_id: Optional[str] = None
) -> Tuple[Optional[str], Optional[str]]:
    """通过 KSampler 的 positive/negative 溯源正、负编码节点 id。"""
    if sampler_id is None:
        sampler_id, _node = find_sampler_node(template)
    sampler = template[sampler_id]
    inputs = sampler.get("inputs") or {}
    pos_id = _trace_encoder(template, _ref_id(inputs.get("positive")), want_prompt=True)
    neg_id = _trace_encoder(template, _ref_id(inputs.get("negative")), want_prompt=True)

    if pos_id is None or neg_id is None:
        # 兜底：按类名收集所有 TextEncode 节点，排除已命中的那个
        encoders = [
            nid for nid, node in template.items()
            if "TextEncode" in str(node.get("class_type") or "")
        ]
        encoders.sort(key=lambda x: (len(x), x))
        for candidate in encoders:
            if pos_id is None and candidate != neg_id:
                pos_id = candidate
                continue
            if neg_id is None and candidate != pos_id:
                neg_id = candidate
    return pos_id, neg_id


def _find_by_classes(
    template: Dict[str, Any], classes: Sequence[str], prefer_ref_of: Optional[Any] = None
) -> Optional[str]:
    """按类名查找节点；若给了 prefer_ref_of（某个连线引用）则优先它指向的节点。"""
    ref = _ref_id(prefer_ref_of)
    if ref and ref in template and template[ref].get("class_type") in classes:
        return ref
    for node_id, node in template.items():
        if node.get("class_type") in classes:
            return node_id
    return None


def _find_size_target(template: Dict[str, Any], sampler_id: str) -> Tuple[Optional[str], Dict[str, Any]]:
    """定位尺寸入口节点：优先被 sampler.latent_image 引用的 EmptyLatentImage，其次 CR 节点。"""
    sampler_inputs = template[sampler_id].get("inputs") or {}
    latent_ref = sampler_inputs.get("latent_image")
    node_id = _find_by_classes(template, LATENT_CLASSES, prefer_ref_of=latent_ref)
    if node_id is None:
        node_id = _find_by_classes(template, SIZE_WIDGET_CLASSES, prefer_ref_of=latent_ref)
    if node_id is None:
        return None, {}
    return node_id, template[node_id]


# --------------------------------------------------------------------------- #
# 图片处理
# --------------------------------------------------------------------------- #
def _decode_data_url(value: str) -> Tuple[bytes, str]:
    """解析 data:image/png;base64,xxxx，返回 (bytes, 建议文件名)。"""
    header, _, payload = value.partition(",")
    ext = "png"
    if "image/" in header:
        ext = header.split("image/")[1].split(";")[0].strip() or "png"
    return base64.b64decode(payload), f"upload_{int(time.time() * 1000)}.{ext}"


def resolve_image_reference(
    item: Any,
    client: Optional["ComfyClient"] = None,
    upload: bool = True,
    warnings: Optional[List[str]] = None,
) -> str:
    """把用户的图片描述解析成 ComfyUI input/ 中的文件名。

    支持三种写法：

    * ``"/path/to/a.png"`` —— 本地路径，会通过 /upload/image 上传（需要 client）
    * ``"already_in_input.jpg"`` —— 已在 ComfyUI input/ 中的文件名，原样使用
    * ``{"path": ...} / {"filename": ...} / {"base64": ...} / {"data_url": ...}``

    ``upload=False``（dry-run）时本地路径只用文件名占位，不产生任何副作用。
    """
    notes = warnings if warnings is not None else []
    tmp_path: Optional[str] = None
    try:
        if isinstance(item, dict):
            if item.get("filename"):
                return str(item["filename"])
            if item.get("path"):
                local = os.path.expanduser(str(item["path"]))
                return _upload_or_name(local, client, upload, notes)
            if item.get("base64") or item.get("data_url"):
                raw = item.get("base64") or item.get("data_url")
                if isinstance(raw, str) and raw.startswith("data:"):
                    content, filename = _decode_data_url(raw)
                else:
                    content = base64.b64decode(str(raw))
                    filename = f"upload_{int(time.time() * 1000)}.png"
                if client is None or not upload:
                    notes.append(f"未上传 base64 图片（{filename}），仅用于预览")
                    return filename
                fd, tmp_path = tempfile.mkstemp(suffix=os.path.splitext(filename)[1] or ".png")
                with os.fdopen(fd, "wb") as fh:
                    fh.write(content)
                info = client.upload_image(tmp_path, overwrite=True)
                return str(info["name"])
            raise ValueError(f"无法识别的图片描述：{item!r}")
        if not isinstance(item, str) or not item.strip():
            raise ValueError(f"图片描述必须是非空字符串或对象：{item!r}")
        value = item.strip()
        if value.startswith("data:"):
            content, filename = _decode_data_url(value)
            if client is None or not upload:
                notes.append(f"未上传 base64 图片（{filename}），仅用于预览")
                return filename
            fd, tmp_path = tempfile.mkstemp(suffix=os.path.splitext(filename)[1] or ".png")
            with os.fdopen(fd, "wb") as fh:
                fh.write(content)
            info = client.upload_image(tmp_path, overwrite=True)
            return str(info["name"])
        if os.path.isfile(os.path.expanduser(value)):
            return _upload_or_name(value, client, upload, notes)
        # 非路径 → 认为已在 ComfyUI input/ 中
        return os.path.basename(value)
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:  # pragma: no cover
                pass


def _upload_or_name(
    local_path: str,
    client: Optional["ComfyClient"],
    upload: bool,
    notes: List[str],
) -> str:
    local_path = os.path.expanduser(local_path)
    if not os.path.isfile(local_path):
        raise FileNotFoundError(f"参考图不存在：{local_path}")
    if client is None or not upload:
        if upload:
            notes.append("dry-run：未上传参考图 " + os.path.basename(local_path))
        return os.path.basename(local_path)
    info = client.upload_image(local_path, overwrite=True)
    subfolder = info.get("subfolder") or ""
    name = str(info["name"])
    return f"{subfolder}/{name}" if subfolder else name


# --------------------------------------------------------------------------- #
# 尺寸/参数解析
# --------------------------------------------------------------------------- #
def parse_size(size: Any) -> Tuple[int, int]:
    """把 "1080x1920" / "1080*1920" / "1080X1920" / [1080,1920] 解析为 (w, h)。"""
    if isinstance(size, (list, tuple)) and len(size) == 2:
        width, height = int(size[0]), int(size[1])
    else:
        text = str(size).strip().lower().replace("*", "x").replace("×", "x")
        if "x" not in text:
            raise ValueError(f"尺寸格式应为 WxH，例如 1080x1920，收到：{size!r}")
        left, _, right = text.partition("x")
        try:
            width, height = int(left), int(right)
        except ValueError as exc:
            raise ValueError(f"尺寸格式应为 WxH，例如 1080x1920，收到：{size!r}") from exc
    if width < 64 or height < 64:
        raise ValueError(f"宽高至少 64，收到：{width}x{height}")
    if width > 8192 or height > 8192:
        raise ValueError(f"宽高最大 8192，收到：{width}x{height}")
    # latent 会按 8 取整，提前对齐避免用户困惑
    if width % 8 or height % 8:
        width -= width % 8
        height -= height % 8
    return width, height


def format_size(width: int, height: int) -> str:
    return f"{width}x{height}"


# --------------------------------------------------------------------------- #
# 构建 API prompt
# --------------------------------------------------------------------------- #
def build_prompt(
    template: Dict[str, Any],
    prompt: str,
    negative: Optional[str] = None,
    images: Optional[Sequence[Any]] = None,
    client: Optional["ComfyClient"] = None,
    no_reference: bool = False,
    seed: Optional[int] = None,
    steps: Optional[int] = None,
    cfg: Optional[float] = None,
    sampler: Optional[str] = None,
    scheduler: Optional[str] = None,
    denoise: Optional[float] = None,
    width: Optional[int] = None,
    height: Optional[int] = None,
    batch_size: Optional[int] = None,
    filename_prefix: Optional[str] = None,
    upload: bool = True,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """在模板副本上套用参数，返回 ``(api_prompt, meta)``。

    ``meta`` 包含实际使用的 seed/尺寸/节点映射，便于回显与复现。
    """
    if not isinstance(prompt, str) or not prompt.strip():
        raise ValueError("prompt 不能为空")
    api = copy.deepcopy(template)
    warnings: List[str] = []

    sampler_id, sampler_node = find_sampler_node(api)
    sampler_inputs = sampler_node.setdefault("inputs", {})
    pos_id, neg_id = find_encoder_nodes(api, sampler_id)
    if pos_id is None:
        raise ValueError("模板中找不到正向文本编码节点（TextEncode*）")
    pos_node = api[pos_id]
    pos_inputs = pos_node.setdefault("inputs", {})

    # --- 正向 / 负向 prompt ---
    pos_inputs["prompt"] = prompt
    if negative is not None:
        if neg_id is None:
            raise ValueError("模板中找不到负向文本编码节点（TextEncode*），无法设置 negative")
        api[neg_id].setdefault("inputs", {})["prompt"] = str(negative)

    # --- 参考图 ---
    image_refs: List[str] = []
    reference_mode = "template-default"
    if no_reference:
        reference_mode = "none"
        if images:
            warnings.append("no_reference=True 时忽略传入的 images")
        for key in ("image1", "image2", "image3"):
            pos_inputs.pop(key, None)
    elif images is not None:
        refs = list(images)
        if len(refs) > MAX_IMAGES:
            raise ValueError(f"参考图最多 {MAX_IMAGES} 张，收到 {len(refs)} 张")
        reference_mode = "custom" if refs else "template-default"
        for index, item in enumerate(refs, start=1):
            name = resolve_image_reference(item, client=client, upload=upload, warnings=warnings)
            image_refs.append(name)
            key = f"image{index}"
            existing = _ref_id(pos_inputs.get(key))
            if existing and existing in api and api[existing].get("class_type") == "LoadImage":
                api[existing].setdefault("inputs", {})["image"] = name
            else:
                new_id = _next_node_id(api)
                api[new_id] = {
                    "class_type": "LoadImage",
                    "inputs": {"image": name},
                    "_meta": {"title": f"参考图{index}"},
                }
                pos_inputs[key] = [new_id, 0]

    # 清理：删除没被任何节点引用的 LoadImage（例如 no_reference 之后）
    referenced: set = set()
    for node in api.values():
        for value in (node.get("inputs") or {}).values():
            ref = _ref_id(value)
            if ref:
                referenced.add(ref)
    for node_id in list(api.keys()):
        if node_id not in referenced and api[node_id].get("class_type") == "LoadImage":
            api.pop(node_id)

    # --- seed 与采样参数 ---
    if seed is None:
        seed = random.randrange(0, 2 ** 63)
    seed = int(seed)
    seed_key = "noise_seed" if "noise_seed" in sampler_inputs else "seed"
    sampler_inputs[seed_key] = seed
    if steps is not None:
        sampler_inputs["steps"] = int(steps)
    if cfg is not None:
        sampler_inputs["cfg"] = float(cfg)
    if sampler is not None:
        sampler_inputs["sampler_name"] = str(sampler)
    if scheduler is not None:
        sampler_inputs["scheduler"] = str(scheduler)
    if denoise is not None:
        sampler_inputs["denoise"] = float(denoise)

    # --- 尺寸 ---
    latent_id, latent_node = _find_size_target(api, sampler_id)
    if width is not None or height is not None or batch_size is not None:
        if latent_id is None:
            raise ValueError("模板中找不到尺寸入口节点（EmptyLatentImage / CR SDXL Aspect Ratio）")
        latent_inputs = latent_node.setdefault("inputs", {})
        if width is not None:
            latent_inputs["width"] = int(width)
        if height is not None:
            latent_inputs["height"] = int(height)
        if batch_size is not None:
            latent_inputs["batch_size"] = int(batch_size)
        if "aspect_ratio" in latent_inputs and "width" in latent_inputs:
            # CR 节点只有在 aspect_ratio=custom 时才使用显式宽高
            latent_inputs["aspect_ratio"] = "custom"
        if "swap_dimensions" in latent_inputs:
            latent_inputs["swap_dimensions"] = "Off"
        if "upscale_factor" in latent_inputs:
            latent_inputs["upscale_factor"] = 1

    # 没显式传尺寸时也要有一份可回显的尺寸信息
    if latent_id is not None:
        latent_inputs = latent_node.get("inputs") or {}
        cur_w = latent_inputs.get("width")
        cur_h = latent_inputs.get("height")
        cur_batch = latent_inputs.get("batch_size", 1)
    else:
        cur_w = cur_h = None
        cur_batch = 1

    # --- 输出前缀 ---
    if filename_prefix is not None:
        save_id = _find_by_classes(api, SAVE_CLASSES)
        if save_id is None:
            warnings.append("模板中没有 SaveImage 节点，filename_prefix 未生效")
        else:
            api[save_id].setdefault("inputs", {})["filename_prefix"] = str(filename_prefix)

    # --- 结果元信息 ---
    load_images: Dict[str, str] = {}
    for node_id, node in api.items():
        if node.get("class_type") == "LoadImage":
            load_images[str(node_id)] = (node.get("inputs") or {}).get("image")
    latent_class = api[latent_id]["class_type"] if latent_id else None
    meta: Dict[str, Any] = {
        "seed": seed,
        "width": cur_w,
        "height": cur_h,
        "size": format_size(cur_w, cur_h) if cur_w and cur_h else None,
        "batch_size": cur_batch,
        "steps": sampler_inputs.get("steps"),
        "cfg": sampler_inputs.get("cfg"),
        "sampler": sampler_inputs.get("sampler_name"),
        "scheduler": sampler_inputs.get("scheduler"),
        "denoise": sampler_inputs.get("denoise"),
        "negative": (api[neg_id].get("inputs") or {}).get("prompt") if neg_id else None,
        "reference_mode": reference_mode,
        "reference_images": image_refs,
        "nodes": {
            "sampler": sampler_id,
            "positive": pos_id,
            "negative": neg_id,
            "latent": latent_id,
            "latent_class": latent_class,
            "save": _find_by_classes(api, SAVE_CLASSES),
            "load_images": load_images,
        },
        "node_count": len(api),
        "prompt_chars": len(prompt),
        "warnings": warnings,
    }
    return api, meta


# --------------------------------------------------------------------------- #
# 端到端
# --------------------------------------------------------------------------- #
def generate(
    prompt: str,
    negative: Optional[str] = None,
    images: Optional[Sequence[Any]] = None,
    output_dir: str = "./outputs",
    base_url: str = DEFAULT_BASE_URL,
    template_path: Optional[str] = None,
    template: Optional[Dict[str, Any]] = None,
    client: Optional["ComfyClient"] = None,
    no_reference: bool = False,
    seed: Optional[int] = None,
    steps: Optional[int] = None,
    cfg: Optional[float] = None,
    sampler: Optional[str] = None,
    scheduler: Optional[str] = None,
    denoise: Optional[float] = None,
    width: Optional[int] = None,
    height: Optional[int] = None,
    batch_size: Optional[int] = None,
    filename_prefix: Optional[str] = None,
    timeout: float = 1800,
    poll_interval: float = 1.0,
    on_progress: Optional[Any] = None,
) -> Dict[str, Any]:
    """构建并执行一次完整的生成任务，返回结果 dict。"""
    tmpl = template if template is not None else load_template(template_path)
    own_client = client is None
    if own_client:
        if ComfyClient is None:  # pragma: no cover
            raise RuntimeError("comfy_client 不可用")
        client = ComfyClient(base_url)

    api, meta = build_prompt(
        tmpl,
        prompt,
        negative=negative,
        images=images,
        client=client,
        no_reference=no_reference,
        seed=seed,
        steps=steps,
        cfg=cfg,
        sampler=sampler,
        scheduler=scheduler,
        denoise=denoise,
        width=width,
        height=height,
        batch_size=batch_size,
        filename_prefix=filename_prefix,
    )
    started = time.time()
    prompt_id = client.queue_prompt(api)
    entry = client.wait(prompt_id, timeout=timeout, poll_interval=poll_interval, on_progress=on_progress)
    downloaded = client.download_outputs(entry, output_dir)
    return {
        "ok": True,
        "prompt_id": prompt_id,
        "elapsed_sec": round(time.time() - started, 2),
        "images": downloaded,
        "meta": meta,
        "output_dir": os.path.abspath(output_dir),
        "base_url": base_url,
    }
