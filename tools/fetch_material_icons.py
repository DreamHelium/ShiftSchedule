#!/usr/bin/env python3
"""批量下载 Material Symbols 图标，并转换成 Android VectorDrawable XML。

背景
----
fonts.google.com/icons 只提供 SVG（以及 PNG / 字体），没有 Android 的
vector XML。Android Studio 的 "Vector Asset" 导入功能是在 GUI 里做
SVG -> XML 的转换；这个脚本用命令行把同一件事做完，适合一次性拉一批图标。

用法
----
    # 最常用：按名字拉取（默认 outlined / default / 24px）
    python3 tools/fetch_material_icons.py calendar_month schedule event_repeat

    # 指定风格与变体（fill1 = 实心）
    python3 tools/fetch_material_icons.py calendar_month --style rounded --variant fill1

    # 从文件读取图标名（每行一个，# 开头为注释）
    python3 tools/fetch_material_icons.py --file tools/icons.txt

    # 走本地代理（clash 默认 7890），两种写法等价
    python3 tools/fetch_material_icons.py calendar_month --proxy http://127.0.0.1:7890
    HTTPS_PROXY=http://127.0.0.1:7890 python3 tools/fetch_material_icons.py calendar_month

    # 只打印 URL 不下载
    python3 tools/fetch_material_icons.py calendar_month --dry-run

输出
----
默认写入 app/src/main/res/drawable/ic_<name>.xml，可直接在 Compose 里用
painterResource(R.drawable.ic_calendar_month)。图标颜色统一输出为黑色，
渲染时用 Icon 的 tint / LocalContentColor 上色即可（Compose 默认就会 tint）。

技术细节
--------
Google 的 Symbols SVG 使用 viewBox="0 -960 960 960"（y 轴负向），而 Android
不支持负的 viewport 原点，所以脚本把 path 包在 <group android:translateY="960">
里平移，而不是改写坐标——与 Android Studio 导入结果渲染一致。

图标本身由 Google 以 Apache-2.0 发布，可随应用分发（保留 NOTICE 署名）。
"""

from __future__ import annotations

import argparse
import html
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

CDN = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbols{style}/{name}/{variant}/{size}px.svg"

STYLES = ("outlined", "rounded", "sharp")
SIZES = (20, 24, 40, 48)

NAME_RE = re.compile(r"^[a-z0-9_]+$")
SVG_ATTR_RE = re.compile(r'([\w:-]+)\s*=\s*"([^"]*)"')
TAG_RE = re.compile(r"<(?P<tag>path|rect|svg)\b(?P<attrs>[^>]*?)/?>", re.S)


def build_url(name: str, style: str, variant: str, size: int) -> str:
    return CDN.format(style=style, name=name, variant=variant, size=size)


def make_opener(proxy: str | None) -> urllib.request.OpenerDirector:
    handlers: list[urllib.request.BaseHandler] = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    else:
        # 未显式指定时沿用环境变量 https_proxy / HTTPS_PROXY
        handlers.append(urllib.request.ProxyHandler())
    return urllib.request.build_opener(*handlers)


def fetch_svg(opener, url: str, timeout: int = 30) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "fetch-material-icons/1.0"})
    with opener.open(req, timeout=timeout) as resp:
        charset = resp.headers.get_content_charset() or "utf-8"
        body = resp.read().decode(charset, errors="replace")
    if not body.lstrip().startswith("<svg"):
        raise ValueError("服务器返回的不是 SVG")
    return body


def parse_attrs(raw: str) -> dict[str, str]:
    return {k: html.unescape(v) for k, v in SVG_ATTR_RE.findall(raw)}


def normalize_color(value: str | None, default: str) -> str:
    """把 SVG 颜色统一成 #AARRGGBB。"""
    if not value or value in ("none", "transparent", "currentColor"):
        return default
    if value.startswith("#"):
        hexpart = value[1:]
        if len(hexpart) == 3:
            hexpart = "".join(c * 2 for c in hexpart)
        if len(hexpart) == 6:
            return "#FF" + hexpart.upper()
        if len(hexpart) == 8:
            return "#" + hexpart.upper()
    # rgb()/named color 等少见的写法就不猜了，回落到默认色
    return default


def svg_to_vector(svg: str, size_dp: int, res_name: str, color: str) -> str:
    viewbox = re.search(r'viewBox="([^"]+)"', svg)
    if not viewbox:
        raise ValueError("SVG 缺少 viewBox，无法确定坐标系")
    try:
        min_x, min_y, vb_w, vb_h = (float(v) for v in viewbox.group(1).replace(",", " ").split())
    except ValueError as exc:
        raise ValueError(f"viewBox 解析失败: {viewbox.group(1)}") from exc

    paths: list[dict[str, str]] = []
    for match in TAG_RE.finditer(svg):
        if match.group("tag") != "path":
            continue  # <rect fill="none"> 之类的背景块直接丢掉
        attrs = parse_attrs(match.group("attrs"))
        data = attrs.get("d", "").strip()
        if not data:
            continue
        if attrs.get("fill", "").strip().lower() == "none":
            continue
        paths.append(
            {
                "d": data,
                "fill": normalize_color(attrs.get("fill"), color),
                "fill_type": "evenOdd" if attrs.get("fill-rule") == "evenodd" else "",
            }
        )

    if not paths:
        raise ValueError("SVG 中没有可用的 <path> 数据")

    def dp(value: float) -> str:
        return f"{value:g}"

    out: list[str] = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {res_name}: Material Symbols，由 tools/fetch_material_icons.py 生成 -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{dp(size_dp)}dp"',
        f'    android:height="{dp(size_dp)}dp"',
        f'    android:viewportWidth="{dp(vb_w)}"',
        f'    android:viewportHeight="{dp(vb_h)}">',
    ]

    # Android 的 viewport 原点只能是 (0,0)，负原点的 viewBox 用 group 平移抵消
    needs_shift = min_x != 0 or min_y != 0
    if needs_shift:
        out.append('    <group')
        if min_x != 0:
            out.append(f'        android:translateX="{dp(-min_x)}"')
        if min_y != 0:
            out.append(f'        android:translateY="{dp(-min_y)}"')
        out.append('        >')

    pad = "        " if needs_shift else "    "
    for path in paths:
        out.append(f"{pad}<path")
        out.append(f'{pad}    android:fillColor="{path["fill"]}"')
        if path["fill_type"]:
            out.append(f'{pad}    android:fillType="{path["fill_type"]}"')
        out.append(f'{pad}    android:pathData="{path["d"]}" />')

    if needs_shift:
        out.append("    </group>")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def read_names(args: argparse.Namespace) -> list[str]:
    names: list[str] = list(args.names)
    if args.file:
        for line in Path(args.file).read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                names.extend(line.split())
    # 去重并保持顺序
    seen: set[str] = set()
    ordered: list[str] = []
    for name in names:
        if name not in seen:
            seen.add(name)
            ordered.append(name)
    return ordered


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="下载 Material Symbols 并转换成 Android VectorDrawable XML",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("names", nargs="*", help="图标名，如 calendar_month")
    parser.add_argument("--file", help="从文件读取图标名（每行一个）")
    parser.add_argument("--style", choices=STYLES, default="outlined", help="默认 outlined")
    parser.add_argument("--variant", default="default", help="default / fill1 / wght300 ...（默认 default）")
    parser.add_argument("--size", type=int, choices=SIZES, default=24, help="SVG 基准尺寸，默认 24")
    parser.add_argument("--color", default="#FF000000", help="填充色，默认 #FF000000（黑）")
    parser.add_argument("--prefix", default="ic_", help="资源名前缀，默认 ic_")
    parser.add_argument(
        "--out",
        default="app/src/main/res/drawable",
        help="输出目录，默认 app/src/main/res/drawable",
    )
    parser.add_argument("--proxy", default=None, help="HTTP(S) 代理，如 http://127.0.0.1:7890")
    parser.add_argument("--save-svg", metavar="DIR", help="顺便把原始 SVG 保存到该目录")
    parser.add_argument("--dry-run", action="store_true", help="只打印 URL，不下载")
    args = parser.parse_args(argv)

    names = read_names(args)
    if not names:
        parser.error("没有指定图标名（位置参数或 --file）")

    bad = [n for n in names if not NAME_RE.match(n)]
    if bad:
        parser.error(f"图标名只能包含小写字母/数字/下划线: {', '.join(bad)}")

    out_dir = Path(args.out)
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        if args.save_svg:
            Path(args.save_svg).mkdir(parents=True, exist_ok=True)

    opener = make_opener(args.proxy)
    failures: list[tuple[str, str]] = []

    for name in names:
        url = build_url(name, args.style, args.variant, args.size)
        res_name = f"{args.prefix}{name}"
        if args.dry_run:
            print(f"{res_name}\t{url}")
            continue
        try:
            svg = fetch_svg(opener, url)
            if args.save_svg:
                (Path(args.save_svg) / f"{name}.svg").write_text(svg, encoding="utf-8")
            xml = svg_to_vector(svg, args.size, res_name, args.color)
            target = out_dir / f"{res_name}.xml"
            target.write_text(xml, encoding="utf-8")
            print(f"✓ {res_name}.xml  ({len(xml)} B)")
        except urllib.error.HTTPError as exc:
            failures.append((name, f"HTTP {exc.code}（图标名拼错了？或该 style/variant 不存在）"))
        except (urllib.error.URLError, TimeoutError) as exc:
            failures.append((name, f"网络错误：{exc}（试试 --proxy http://127.0.0.1:7890）"))
        except Exception as exc:  # noqa: BLE001 - 单个图标失败不影响其他图标
            failures.append((name, str(exc)))

    if failures:
        print("\n以下图标失败：", file=sys.stderr)
        for name, reason in failures:
            print(f"  ✗ {name}: {reason}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
