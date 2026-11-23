#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Qwen-Image-Edit 命令行提交器。

示例::

    python3 cli.py -p "把背景换成雪山夜景" -o ./outputs --seed 123 --steps 4 --size 1080x1920
    python3 cli.py -p "换海边背景" --size 768x768 --dry-run
    python3 cli.py -p "用这张图改" -i ./my_ref.png --size 1024x1024 --json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from typing import Any, Dict, List, Optional

from comfy_client import ComfyClient, ComfyConnectionError, ComfyError, ComfyTimeout
from runner import (
    DEFAULT_BASE_URL,
    DEFAULT_TEMPLATE,
    build_prompt,
    format_size,
    generate,
    load_template,
    parse_size,
)

__all__ = ["main"]


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="cli.py",
        description="提交 Qwen-Image-Edit 工作流到本地 ComfyUI，并把结果图片落盘。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python3 cli.py -p '把背景换成雪山夜景' --size 1080x1920\n"
            "  python3 cli.py -p '换成海边' -i ./ref.png --size 768x768 --seed 42\n"
            "  python3 cli.py -p 'test' --dry-run | jq .\n"
        ),
    )
    parser.add_argument("-p", "--prompt", required=True, help="正向提示词（必填）")
    parser.add_argument("-n", "--negative", default=None, help="负向提示词（默认沿用模板的空串）")
    parser.add_argument(
        "-i", "--image", action="append", default=None, metavar="PATH_OR_NAME",
        help="参考图：本地路径（会自动上传）或 ComfyUI input/ 中已有文件名，可重复，最多 3 张",
    )
    parser.add_argument("--no-reference", action="store_true", help="不使用参考图（纯文生图）")
    parser.add_argument("--seed", type=int, default=None, help="随机种子，缺省随机（0..2^63-1）")
    parser.add_argument("--steps", type=int, default=None, help="采样步数（Qwen-Rapid 建议 4）")
    parser.add_argument("--cfg", type=float, default=None, help="CFG（Qwen-Rapid 建议 1.0）")
    parser.add_argument("--sampler", default=None, help="采样器名，如 euler / dpmpp_2m")
    parser.add_argument("--scheduler", default=None, help="调度器名，如 simple / karras")
    parser.add_argument("--denoise", type=float, default=None, help="去噪强度 0..1")
    parser.add_argument("--size", default=None, metavar="WxH", help="输出尺寸，如 1080x1920")
    parser.add_argument("--width", type=int, default=None, help="宽度（与 --height 一起用）")
    parser.add_argument("--height", type=int, default=None, help="高度")
    parser.add_argument("--batch", "--batch-size", dest="batch_size", type=int, default=None,
                        help="一次生成几张（默认 1）")
    parser.add_argument("--filename-prefix", default=None, help="SaveImage 的文件名前缀")
    parser.add_argument("-o", "--output-dir", default="./outputs", help="结果图片保存目录，默认 ./outputs")
    parser.add_argument("--timeout", type=float, default=1800, help="等待超时秒数，默认 1800")
    parser.add_argument("--poll-interval", type=float, default=1.0, help="轮询间隔秒数，默认 1.0")
    parser.add_argument("--template", default=None, help=f"API 模板路径，默认 {DEFAULT_TEMPLATE}")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"ComfyUI 地址，默认 {DEFAULT_BASE_URL}")
    parser.add_argument("--dry-run", action="store_true", help="只打印最终 API prompt JSON，不提交任务")
    parser.add_argument("--json", action="store_true", help="只输出机器可读 JSON 结果")
    parser.add_argument("-q", "--quiet", action="store_true", help="不打印进度")
    return parser


def _human_size(num: Optional[int]) -> str:
    if not num:
        return "-"
    value = float(num)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}"
        value /= 1024
    return f"{value:.1f} GB"


def _print_summary(result: Dict[str, Any], elapsed_wall: float) -> None:
    meta = result.get("meta") or {}
    print("✓ 生成完成")
    print(f"  prompt_id : {result.get('prompt_id')}")
    print(f"  任务耗时   : {result.get('elapsed_sec')} s（含排队；总 wall time {elapsed_wall:.2f} s）")
    print(f"  尺寸       : {meta.get('size')}    batch_size={meta.get('batch_size')}")
    print(f"  参数       : seed={meta.get('seed')} steps={meta.get('steps')} "
          f"cfg={meta.get('cfg')} sampler={meta.get('sampler')} scheduler={meta.get('scheduler')} "
          f"denoise={meta.get('denoise')}")
    refs = meta.get("reference_images") or []
    if meta.get("reference_mode") == "none":
        print("  参考图     : 无（纯文生图）")
    elif refs:
        print(f"  参考图     : {', '.join(map(str, refs))}")
    else:
        load_images = (meta.get("nodes") or {}).get("load_images") or {}
        print(f"  参考图     : 模板默认 {', '.join(map(str, load_images.values()))}")
    print(f"  节点数     : {meta.get('node_count')}")
    for warning in meta.get("warnings") or []:
        print(f"  ! {warning}")
    print(f"  输出目录   : {result.get('output_dir')}")
    images = result.get("images") or []
    if not images:
        print("  （没有收到任何图片）")
    for item in images:
        print(f"    - {item.get('path')}  ({_human_size(item.get('size_bytes'))})")


def main(argv: Optional[List[str]] = None) -> int:
    args = _build_arg_parser().parse_args(argv)
    wall_start = time.time()

    # --- 参数校验 ---
    width = height = None
    if args.size:
        try:
            width, height = parse_size(args.size)
        except ValueError as exc:
            print(f"[参数错误] {exc}", file=sys.stderr)
            return 2
    if args.width is not None or args.height is not None:
        if args.size:
            print("[参数错误] --size 与 --width/--height 只能二选一", file=sys.stderr)
            return 2
        if args.width is None or args.height is None:
            print("[参数错误] --width 与 --height 需要同时提供", file=sys.stderr)
            return 2
        try:
            width, height = parse_size(f"{args.width}x{args.height}")
        except ValueError as exc:
            print(f"[参数错误] {exc}", file=sys.stderr)
            return 2

    images: Optional[List[Any]] = args.image
    if images is not None and len(images) > 3:
        print("[参数错误] 参考图最多 3 张（-i 最多重复 3 次）", file=sys.stderr)
        return 2
    if args.no_reference and images:
        print("[参数错误] --no-reference 与 -i 互斥", file=sys.stderr)
        return 2

    # --- 模板 ---
    try:
        template = load_template(args.template)
    except (OSError, ValueError) as exc:
        print(f"[模板错误] {exc}", file=sys.stderr)
        return 2

    # --- dry-run：只打印 API prompt ---
    if args.dry_run:
        try:
            api, meta = build_prompt(
                template, args.prompt, negative=args.negative, images=images,
                client=None, no_reference=args.no_reference, upload=False,
                seed=args.seed, steps=args.steps, cfg=args.cfg, sampler=args.sampler,
                scheduler=args.scheduler, denoise=args.denoise,
                width=width, height=height, batch_size=args.batch_size,
                filename_prefix=args.filename_prefix,
            )
        except (ValueError, FileNotFoundError) as exc:
            print(f"[参数错误] {exc}", file=sys.stderr)
            return 2
        if args.json:
            print(json.dumps({"ok": True, "dry_run": True, "prompt": api, "meta": meta},
                             ensure_ascii=False, indent=2))
        else:
            print(f"# dry-run：{len(api)} 个节点，seed={meta['seed']}，size={meta['size']}，"
                  f"参考图模式={meta['reference_mode']}（未提交任何任务）", file=sys.stderr)
            print(json.dumps(api, ensure_ascii=False, indent=2))
        return 0

    # --- 真实执行 ---
    client = ComfyClient(args.base_url)
    progress_cb = None
    if not (args.quiet or args.json):
        last = {"t": 0.0}

        def progress_cb(info: Dict[str, Any]) -> None:  # type: ignore[misc]
            now = time.time()
            if now - last["t"] < 2:
                return
            last["t"] = now
            pos = info.get("queue_position")
            where = "执行中" if pos == 0 else f"排队第 {pos} 位"
            print(f"  … {info.get('elapsed_sec')}s {where}", file=sys.stderr)

    try:
        result = generate(
            args.prompt,
            negative=args.negative,
            images=images,
            output_dir=args.output_dir,
            base_url=args.base_url,
            template=template,
            client=client,
            no_reference=args.no_reference,
            seed=args.seed,
            steps=args.steps,
            cfg=args.cfg,
            sampler=args.sampler,
            scheduler=args.scheduler,
            denoise=args.denoise,
            width=width,
            height=height,
            batch_size=args.batch_size,
            filename_prefix=args.filename_prefix,
            timeout=args.timeout,
            poll_interval=args.poll_interval,
            on_progress=progress_cb,
        )
    except ComfyTimeout as exc:
        print(f"[超时] {exc}", file=sys.stderr)
        return 3
    except ComfyConnectionError as exc:
        print(f"[连接失败] {exc}\n  提示：确认 ComfyUI 已在 {args.base_url} 运行", file=sys.stderr)
        return 4
    except ComfyError as exc:
        print(f"[ComfyUI 错误] {exc}", file=sys.stderr)
        if exc.detail:
            print(f"  detail: {json.dumps(exc.detail, ensure_ascii=False)[:2000]}", file=sys.stderr)
        return 1
    except (ValueError, FileNotFoundError) as exc:
        print(f"[参数错误] {exc}", file=sys.stderr)
        return 2

    elapsed_wall = time.time() - wall_start
    if args.json:
        payload = dict(result)
        payload["elapsed_wall_sec"] = round(elapsed_wall, 2)
        payload.pop("history", None)
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        _print_summary(result, elapsed_wall)

    return 0 if result.get("images") else 1


if __name__ == "__main__":
    raise SystemExit(main())
