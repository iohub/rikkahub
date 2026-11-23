#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ComfyUI UI 格式工作流 → API 格式（/prompt 可直接提交）转换器。

纯标准库实现。核心规则：

* 节点 key 使用原 node id 的字符串形式，``class_type`` = UI 里的 ``type``；
* widget 值优先取 UI JSON 里的 ``widgets_values_named``（按名映射，权威），
  没有时按 ``object_info`` 的输入顺序做位置映射，并跳过前端专用 widget
  （``control_after_generate`` / ``upload`` 等）；
* 连线生成 ``[srcNodeId, srcSlot]``；
* ``mode == 4``（bypass）：丢弃该节点；若下游仍引用其输出，且该节点内部存在
  与输出同类型的输入，则直通到上游，否则下游该输入保持未连接；
* ``mode == 2``（mute）：丢弃该节点，引用其输出的下游输入不填；
* ``Reroute`` 穿透、``PrimitiveNode`` 把值直接写进下游 widget 输入。

命令行::

    python3 ui_to_api.py ui.json -o api.json
    python3 ui_to_api.py ui.json --base-url http://127.0.0.1:8188 --verbose
    python3 ui_to_api.py ui.json --offline          # 不访问 ComfyUI，用内置表
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

try:  # 同目录导入
    from comfy_client import ComfyClient, ComfyError
except ImportError:  # pragma: no cover
    ComfyClient = None  # type: ignore
    ComfyError = Exception  # type: ignore

__all__ = [
    "ConversionReport",
    "is_widget_input",
    "widget_names_for_class",
    "ui_to_api",
    "load_object_info",
]

DEFAULT_BASE_URL = "http://127.0.0.1:8188"

# 前端专用、不属于 API 输入的 widget 名（位置映射时强制跳过）
FRONTEND_ONLY_WIDGETS = {
    "control_after_generate",
    "upload",
    "image_upload",
    "videopreview",
    "control_before_generate",
}

# 纯连线类型（不是 widget）
CONNECTION_TYPES = {
    "MODEL", "CLIP", "VAE", "IMAGE", "MASK", "LATENT", "CONDITIONING", "CONTROL_NET",
    "STYLE_MODEL", "CLIP_VISION", "CLIP_VISION_OUTPUT", "GLIGEN", "SAMPLER", "SIGMAS",
    "NOISE", "GUIDER", "UPSCALE_MODEL", "LORA_MODEL", "AUDIO", "VIDEO", "MODEL_PATCH",
    "T2I_ADAPTER", "VOXEL", "WEBCAM", "SVG", "POINTCLOUD", "FILE_3D", "PHOTOMAKER",
    "BASIC_PIPE", "COMBO_PIPE", "CHECKPOINT_LOADER_OUTPUT",
}

# 离线兜底：常见节点的 widget 输入顺序（拿不到 object_info 时使用）
OFFLINE_OBJECT_INFO: Dict[str, Dict[str, Any]] = {
    "KSampler": {
        "input": {
            "required": {
                "model": ["MODEL"], "seed": ["INT", {}], "steps": ["INT", {}],
                "cfg": ["FLOAT", {}], "sampler_name": ["COMBO", {}],
                "scheduler": ["COMBO", {}], "positive": ["CONDITIONING"],
                "negative": ["CONDITIONING"], "latent_image": ["LATENT"],
                "denoise": ["FLOAT", {}],
            }
        }
    },
    "KSamplerAdvanced": {
        "input": {
            "required": {
                "add_noise": ["COMBO", {}], "noise_seed": ["INT", {}], "steps": ["INT", {}],
                "cfg": ["FLOAT", {}], "sampler_name": ["COMBO", {}], "scheduler": ["COMBO", {}],
                "positive": ["CONDITIONING"], "negative": ["CONDITIONING"],
                "latent_image": ["LATENT"], "start_at_step": ["INT", {}],
                "end_at_step": ["INT", {}], "return_with_leftover_noise": ["COMBO", {}],
                "model": ["MODEL"],
            }
        }
    },
    "SaveImage": {
        "input": {"required": {"images": ["IMAGE"], "filename_prefix": ["STRING", {}]}}
    },
    "PreviewImage": {"input": {"required": {"images": ["IMAGE"]}}},
    "LoadImage": {
        "input": {"required": {"image": ["COMBO", {"image_upload": True}]}}
    },
    "EmptyLatentImage": {
        "input": {"required": {"width": ["INT", {}], "height": ["INT", {}], "batch_size": ["INT", {}]}}
    },
    "VAEDecode": {"input": {"required": {"samples": ["LATENT"], "vae": ["VAE"]}}},
    "VAEEncode": {"input": {"required": {"pixels": ["IMAGE"], "vae": ["VAE"]}}},
    "VAELoader": {"input": {"required": {"vae_name": ["COMBO", {}]}}},
    "CLIPLoader": {
        "input": {
            "required": {"clip_name": ["COMBO", {}], "type": ["COMBO", {}]},
            "optional": {"device": ["COMBO", {}]},
        }
    },
    "UnetLoaderGGUF": {"input": {"required": {"unet_name": ["COMBO", {}]}}},
    "CheckpointLoaderSimple": {
        "input": {"required": {"ckpt_name": ["COMBO", {}]}}
    },
    "CLIPTextEncode": {"input": {"required": {"text": ["STRING", {}], "clip": ["CLIP"]}}},
    "TextEncodeQwenImageEditPlus": {
        "input": {
            "required": {"clip": ["CLIP"], "prompt": ["STRING", {}]},
            "optional": {"vae": ["VAE"], "image1": ["IMAGE"], "image2": ["IMAGE"], "image3": ["IMAGE"]},
        }
    },
    "CR Text": {"input": {"required": {"text": ["STRING", {}]}}},
    "CR Text Concatenate": {
        "input": {
            "required": {},
            "optional": {
                "text1": ["STRING", {"forceInput": True}],
                "text2": ["STRING", {"forceInput": True}],
                "separator": ["STRING", {}],
            },
        }
    },
    "CR SDXL Aspect Ratio": {
        "input": {
            "required": {
                "width": ["INT", {}], "height": ["INT", {}], "aspect_ratio": ["COMBO", {}],
                "swap_dimensions": ["COMBO", {}], "upscale_factor": ["FLOAT", {}],
                "batch_size": ["INT", {}],
            }
        }
    },
    "Reroute": {"input": {"required": {}}},
    "PrimitiveNode": {"input": {"required": {}}},
    "Note": {"input": {"required": {}}},
}


# --------------------------------------------------------------------------- #
# object_info 工具
# --------------------------------------------------------------------------- #
def _spec_options(spec: Any) -> Dict[str, Any]:
    if isinstance(spec, (list, tuple)) and len(spec) > 1 and isinstance(spec[1], dict):
        return spec[1]
    return {}


def spec_widget_kind(spec: Any) -> Optional[str]:
    """判断输入定义是否为 widget，返回其 kind（INT/FLOAT/STRING/BOOLEAN/COMBO）。"""
    if not isinstance(spec, (list, tuple)) or not spec:
        return None
    options = _spec_options(spec)
    if options.get("forceInput"):
        return None
    dtype = spec[0]
    if isinstance(dtype, (list, tuple)):
        return "COMBO"
    if not isinstance(dtype, str):
        return None
    base = dtype.split("(")[0].strip().upper()
    if base in ("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO"):
        return base
    if base in CONNECTION_TYPES or base == "*":
        return None
    return None


def spec_combo_options(spec: Any) -> List[Any]:
    """取出 COMBO 的可选值列表（兼容旧版 [ [..], {} ] 与新版 ["COMBO", {"options": [...]}]）。"""
    if not isinstance(spec, (list, tuple)) or not spec:
        return []
    dtype = spec[0]
    if isinstance(dtype, (list, tuple)):
        return list(dtype)
    options = _spec_options(spec)
    for key in ("options", "values"):
        values = options.get(key)
        if isinstance(values, (list, tuple)):
            return list(values)
    return []


def class_input_specs(cls_info: Optional[Dict[str, Any]]) -> List[Tuple[str, Any, str]]:
    """返回 [(输入名, spec, required|optional)]，保持 object_info 的声明顺序。"""
    if not isinstance(cls_info, dict):
        return []
    inputs = cls_info.get("input")
    if not isinstance(inputs, dict):
        return []
    ordered: List[Tuple[str, Any, str]] = []
    for group in ("required", "optional"):
        group_specs = inputs.get(group) or {}
        if isinstance(group_specs, dict):
            for name, spec in group_specs.items():
                ordered.append((name, spec, group))
    return ordered


def is_widget_input(cls_info: Optional[Dict[str, Any]], name: str) -> bool:
    for in_name, spec, _group in class_input_specs(cls_info):
        if in_name == name:
            return spec_widget_kind(spec) is not None
    return False


def widget_names_for_class(cls_info: Optional[Dict[str, Any]]) -> List[Tuple[str, str, Any]]:
    """按声明顺序返回该节点的 widget 输入 [(名字, kind, spec)]。"""
    result: List[Tuple[str, str, Any]] = []
    for name, spec, _group in class_input_specs(cls_info):
        kind = spec_widget_kind(spec)
        if kind and name not in FRONTEND_ONLY_WIDGETS:
            result.append((name, kind, spec))
    return result


def load_object_info(
    base_url: str = DEFAULT_BASE_URL,
    offline: bool = False,
    object_info_file: Optional[str] = None,
) -> Dict[str, Any]:
    """加载 object_info：优先级 文件 > HTTP > 内置兜底表。"""
    if object_info_file:
        with open(object_info_file, "r", encoding="utf-8") as fh:
            return json.load(fh)
    if not offline and ComfyClient is not None:
        try:
            client = ComfyClient(base_url)
            data = client.object_info()
            if isinstance(data, dict) and data:
                return data
        except Exception as exc:  # 连不上就用内置表
            print(f"[warn] 无法读取 {base_url}/object_info（{exc}），改用内置节点表", file=sys.stderr)
    return OFFLINE_OBJECT_INFO


# --------------------------------------------------------------------------- #
# 转换
# --------------------------------------------------------------------------- #
class ConversionReport:
    """转换过程的统计信息，便于 CLI 打印与自测断言。"""

    def __init__(self) -> None:
        self.total_nodes = 0
        self.converted: List[str] = []
        self.bypassed: List[str] = []
        self.muted: List[str] = []
        self.rerouted: List[str] = []
        self.primitives: List[str] = []
        self.ignored: List[str] = []
        self.warnings: List[str] = []

    def as_dict(self) -> Dict[str, Any]:
        return {
            "total_nodes": self.total_nodes,
            "converted": self.converted,
            "bypassed": self.bypassed,
            "muted": self.muted,
            "rerouted": self.rerouted,
            "primitives": self.primitives,
            "ignored": self.ignored,
            "warnings": self.warnings,
        }


def _parse_links(raw_links: Any) -> Dict[Any, Dict[str, Any]]:
    """把 links 数组（或新版对象列表）标准化成 {link_id: {...}}。"""
    links: Dict[Any, Dict[str, Any]] = {}
    for item in raw_links or []:
        if isinstance(item, (list, tuple)) and len(item) >= 5:
            link_id, origin_id, origin_slot, target_id, target_slot = item[:5]
            link_type = item[5] if len(item) > 5 else None
        elif isinstance(item, dict):
            link_id = item.get("id")
            origin_id = item.get("origin_id", item.get("source"))
            origin_slot = item.get("origin_slot", item.get("sourceSlot"))
            target_id = item.get("target_id", item.get("target"))
            target_slot = item.get("target_slot", item.get("targetSlot"))
            link_type = item.get("type")
        else:
            continue
        links[link_id] = {
            "id": link_id,
            "origin_id": origin_id,
            "origin_slot": origin_slot,
            "target_id": target_id,
            "target_slot": target_slot,
            "type": link_type,
        }
    return links


def _value_kind(value: Any) -> str:
    if isinstance(value, bool):
        return "BOOLEAN"
    if isinstance(value, int):
        return "INT"
    if isinstance(value, float):
        return "FLOAT"
    if isinstance(value, str):
        return "STRING"
    return "OTHER"


def _kind_matches(kind: str, value: Any) -> bool:
    """位置映射时判断某个值是否可能属于期望类型的 widget。"""
    if kind == "INT":
        return isinstance(value, int) and not isinstance(value, bool)
    if kind == "FLOAT":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if kind == "STRING":
        return isinstance(value, str)
    if kind == "BOOLEAN":
        return isinstance(value, bool)
    if kind == "COMBO":
        return isinstance(value, (str, int, float)) and not isinstance(value, bool)
    return True


def _match_combo(kind: str, spec: Any, value: Any) -> bool:
    """COMBO 的更严格匹配：值应属于候选集（候选集为空时退化为类型匹配）。"""
    if kind != "COMBO":
        return _kind_matches(kind, value)
    options = spec_combo_options(spec)
    if options:
        try:
            return value in options
        except TypeError:  # pragma: no cover
            return False
    return _kind_matches(kind, value)


def map_widgets_positional(
    widgets_values: List[Any], widgets: List[Tuple[str, str, Any]], node_id: Any
) -> Tuple[Dict[str, Any], List[str]]:
    """位置映射：把 widgets_values 对齐到 [(名字, kind, spec)]。

    采用"类型指纹 + 跳过前端专用 widget"的启发式：对每个期望的 widget，
    从当前位置向后找第一个类型兼容的值；中间被跳过的项视为前端专用 widget。
    """
    values: Dict[str, Any] = {}
    skipped: List[str] = []
    cursor = 0
    for name, kind, spec in widgets:
        found = -1
        scan = cursor
        while scan < len(widgets_values):
            candidate = widgets_values[scan]
            if _match_combo(kind, spec, candidate) or (
                # 第一项直接尝试宽松匹配，避免因候选集不含该值而整体错位
                scan == cursor and _kind_matches(kind, candidate)
            ):
                found = scan
                break
            scan += 1
        if found < 0:
            continue
        skipped.extend(str(v) for v in widgets_values[cursor:found])
        values[name] = widgets_values[found]
        cursor = found + 1
    skipped.extend(str(v) for v in widgets_values[cursor:])
    return values, skipped


def ui_to_api(
    ui_workflow: Dict[str, Any],
    object_info: Optional[Dict[str, Any]] = None,
    verbose: bool = False,
) -> Tuple[Dict[str, Any], ConversionReport]:
    """把 UI 格式工作流转换成 API 格式。

    返回 ``(api_prompt, report)``。``api_prompt`` 可直接 POST 到 /prompt。
    """
    object_info = object_info or OFFLINE_OBJECT_INFO
    report = ConversionReport()
    nodes_raw = ui_workflow.get("nodes") or []
    links = _parse_links(ui_workflow.get("links"))
    nodes: Dict[Any, Dict[str, Any]] = {}
    for node in nodes_raw:
        if not isinstance(node, dict) or node.get("id") is None:
            continue
        nodes[node["id"]] = node
    report.total_nodes = len(nodes)

    def node_output_type(node: Dict[str, Any], slot: int) -> Optional[str]:
        outputs = node.get("outputs") or []
        if 0 <= slot < len(outputs):
            otype = outputs[slot].get("type")
            if isinstance(otype, (list, tuple)):
                return str(otype[0]) if otype else None
            return str(otype) if otype is not None else None
        return None

    def node_input_type(node: Dict[str, Any], entry: Dict[str, Any]) -> Optional[str]:
        itype = entry.get("type")
        if isinstance(itype, (list, tuple)):
            return str(itype[0]) if itype else None
        if itype:
            return str(itype)
        # 退化：用 object_info 的定义
        cls_info = object_info.get(str(node.get("type")))
        for name, spec, _group in class_input_specs(cls_info):
            if name == entry.get("name"):
                dtype = spec[0] if isinstance(spec, (list, tuple)) and spec else None
                if isinstance(dtype, str):
                    return dtype
        return None

    def types_compatible(out_type: Optional[str], in_type: Optional[str]) -> bool:
        if out_type is None or in_type is None:
            return False
        if out_type == "*" or in_type == "*":
            return True
        return out_type.upper() == in_type.upper()

    def resolve(link_id: Any, stack: Optional[Set[Tuple[Any, Any]]] = None) -> Any:
        """解析一条连线，返回 ("link", [id, slot]) / ("value", v) / None。"""
        if link_id is None:
            return None
        stack = stack or set()
        link = links.get(link_id)
        if not link:
            return None
        origin_id, origin_slot = link["origin_id"], link["origin_slot"]
        key = (origin_id, origin_slot)
        if key in stack:  # 防环
            return None
        origin = nodes.get(origin_id)
        if origin is None:
            return None
        ntype = str(origin.get("type") or "")
        mode = origin.get("mode", 0) or 0

        if ntype == "Reroute":
            for entry in origin.get("inputs") or []:
                if entry.get("link") is not None:
                    return resolve(entry["link"], stack | {key})
            return None

        if ntype == "PrimitiveNode":
            values = origin.get("widgets_values") or []
            return ("value", values[0] if values else None)

        if mode == 2:  # mute：下游不连
            return None

        if mode == 4:  # bypass：尝试同类型输入直通
            out_type = node_output_type(origin, int(origin_slot or 0))
            for entry in origin.get("inputs") or []:
                if entry.get("link") is None:
                    continue
                if types_compatible(out_type, node_input_type(origin, entry)):
                    return resolve(entry["link"], stack | {key})
            return None

        return ("link", [str(origin_id), int(origin_slot or 0)])

    api: Dict[str, Any] = {}
    for node_id in nodes:
        node = nodes[node_id]
        ntype = str(node.get("type") or "")
        mode = node.get("mode", 0) or 0

        if ntype == "Reroute":
            report.rerouted.append(str(node_id))
            continue
        if ntype == "PrimitiveNode":
            report.primitives.append(str(node_id))
            continue
        if ntype in ("Note", "MarkdownNote"):
            report.ignored.append(str(node_id))
            continue
        if mode == 4:
            report.bypassed.append(str(node_id))
            continue
        if mode == 2:
            report.muted.append(str(node_id))
            continue

        cls_info = object_info.get(ntype)
        if cls_info is None:
            report.warnings.append(f"节点 {node_id} 的类型 {ntype} 不在 object_info 中，仍按原样转换")

        inputs: Dict[str, Any] = {}

        # 1) widget 值：优先 widgets_values_named
        widgets = widget_names_for_class(cls_info)
        widget_values: Dict[str, Any] = {}
        named = node.get("widgets_values_named")
        positional = node.get("widgets_values")
        if isinstance(named, dict) and named:
            valid_names = {name for name, _k, _s in widgets}
            for key, value in named.items():
                if key in FRONTEND_ONLY_WIDGETS:
                    continue
                if key in valid_names or cls_info is None:
                    widget_values[key] = value
                else:
                    # 名字不在该类的 widget 列表里：可能是前端专用或 optional widget
                    if key in {n for n, _s, _g in class_input_specs(cls_info)}:
                        widget_values[key] = value
        elif isinstance(positional, list) and widgets:
            widget_values, skipped = map_widgets_positional(positional, widgets, node_id)
            if verbose and skipped:
                joined = ", ".join(skipped)
                report.warnings.append(f"节点 {node_id}({ntype}) 跳过未对齐的 widget 值：{joined}")
        elif isinstance(positional, list) and positional and cls_info is None:
            report.warnings.append(f"节点 {node_id}({ntype}) 缺少 object_info，无法映射 widget 值")
        inputs.update(widget_values)

        # 2) 连线覆盖 widget 值
        for entry in node.get("inputs") or []:
            if not isinstance(entry, dict):
                continue
            name = entry.get("name")
            if not name:
                continue
            link_id = entry.get("link")
            if link_id is None:
                continue
            resolved = resolve(link_id)
            if resolved is None:
                # 上游被 mute / bypass 且无法直通：该输入保持未连接
                continue
            kind, payload = resolved
            inputs[name] = payload

        api[str(node_id)] = {"class_type": ntype, "inputs": inputs}
        report.converted.append(str(node_id))

    return api, report


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="ComfyUI UI 格式工作流 → API 格式（可直接 POST /prompt）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n  python3 ui_to_api.py ui.json -o api.json\n"
               "  python3 ui_to_api.py ui.json --offline --verbose > api.json",
    )
    parser.add_argument("ui_json", help="UI 格式工作流 JSON 路径")
    parser.add_argument("-o", "--output", help="输出文件路径（缺省打印到 stdout）")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"ComfyUI 地址，默认 {DEFAULT_BASE_URL}")
    parser.add_argument("--object-info-file", help="从文件读取 object_info（离线用）")
    parser.add_argument("--offline", action="store_true", help="不访问 ComfyUI，使用内置节点表")
    parser.add_argument("--indent", type=int, default=2, help="输出 JSON 缩进，默认 2")
    parser.add_argument("--verbose", action="store_true", help="在 stderr 打印转换报告")
    parser.add_argument("--report", action="store_true", help="只打印转换报告（JSON），不输出工作流")
    parser.add_argument("--compact", action="store_true", help="单行压缩输出")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = _build_arg_parser().parse_args(argv)
    try:
        with open(args.ui_json, "r", encoding="utf-8") as fh:
            ui_workflow = json.load(fh)
    except OSError as exc:
        print(f"[error] 无法读取 UI 工作流：{exc}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"[error] UI 工作流不是合法 JSON：{exc}", file=sys.stderr)
        return 2

    if not isinstance(ui_workflow, dict) or "nodes" not in ui_workflow:
        print("[error] 该文件不是 ComfyUI UI 格式（缺少 nodes 字段）", file=sys.stderr)
        return 2

    object_info = load_object_info(
        base_url=args.base_url,
        offline=args.offline,
        object_info_file=args.object_info_file,
    )
    api, report = ui_to_api(ui_workflow, object_info, verbose=args.verbose)

    if args.report:
        text = json.dumps(report.as_dict(), ensure_ascii=False, indent=2)
        print(text)
        return 0

    indent = None if args.compact else args.indent
    text = json.dumps(api, ensure_ascii=False, indent=indent)

    if args.output:
        out_dir = os.path.dirname(os.path.abspath(args.output))
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text + ("\n" if not args.compact else ""))
        if args.verbose:
            print(
                f"[ok] 已写出 {len(api)} 个节点 -> {os.path.abspath(args.output)}\n"
                f"     bypass={report.bypassed} muted={report.muted} "
                f"reroute={report.rerouted} primitive={report.primitives}\n"
                f"     ignored={report.ignored}\n"
                + "".join(f"     warn: {w}\n" for w in report.warnings),
                file=sys.stderr,
            )
    else:
        print(text)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
