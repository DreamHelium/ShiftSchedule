#!/usr/bin/env python3
"""批量下载 Material Symbols 图标（官方 Android XML 或原始 SVG），放进 res/drawable。

为什么要这个脚本
----------------
fonts.google.com/icons 上的图标可以逐个下载，但：
  1. 一次只能下一个，补一整套很烦；
  2. 官网给的 Android XML 带 `android:tint="?attr/colorControlNormal"`，
     纯 Compose 项目（没有 appcompat / material-components）会资源链接失败：
     error: resource attr/colorControlNormal (aka <pkg>:attr/colorControlNormal) not found.
  3. 文件名带 `_24px` 后缀，还得手动改。

这个脚本直接命中 Google 的 CDN（与浏览器下载的是同一份文件，可校验 sha256），
默认输出已经去掉 tint 的 Android XML，可直接用。

用法
----
    # 下载（默认 outlined / default / 24px，官方 Android XML）
    python3 tools/fetch_material_icons.py calendar_month schedule event_repeat

    # 走本地代理（clash 默认 7890）
    python3 tools/fetch_material_icons.py calendar_month --proxy http://127.0.0.1:7890
    HTTPS_PROXY=http://127.0.0.1:7890 python3 tools/fetch_material_icons.py calendar_month

    # 别的风格 / 实心变体 / 48px
    python3 tools/fetch_material_icons.py notifications --style rounded --variant fill1 --size 48

    # 从文件批量（每行一个名字，# 开头是注释）
    python3 tools/fetch_material_icons.py --file tools/icons.txt

    # 搜索图标名（下载官方元数据，支持按名字/tag/分类匹配）
    python3 tools/fetch_material_icons.py --search night --proxy http://127.0.0.1:7890

    # 只打印 URL / 顺便保存原始文件 / 用 SVG 而不是官方 XML
    python3 tools/fetch_material_icons.py alarm --dry-run
    python3 tools/fetch_material_icons.py alarm --save-raw /tmp/raw
    python3 tools/fetch_material_icons.py alarm --format svg

输出
----
默认写入 app/src/main/res/drawable/ic_<name>.xml，Compose 里用
painterResource(R.drawable.ic_calendar_month)，颜色由 Icon(tint = ...) 决定。

名字 → URL 规则（已实测）
------------------------
  https://fonts.gstatic.com/s/i/short-term/release/
      materialsymbols{style}/{name}/{variant}/{size}px.{xml|svg}
  style   ∈ outlined / rounded / sharp   （没有 filled，实心用 variant=fill1）
  variant ∈ default / fill1 / wght300 ...
  size    ∈ 20 / 24 / 40 / 48
元数据：https://fonts.google.com/metadata/icons?key=material_symbols

图标由 Google 以 Apache-2.0 发布，可随应用分发（保留 NOTICE 署名即可）。
"""

from __future__ import annotations

import argparse
import gzip
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CDN = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbols{style}/{name}/{variant}/{size}px.{ext}"
METADATA = "https://fonts.google.com/metadata/icons?key=material_symbols&incomplete=true"

STYLES = ("outlined", "rounded", "sharp")
SIZES = (20, 24, 40, 48)

NAME_RE = re.compile(r"^[a-z0-9_]+$")
SVG_ATTR_RE = re.compile(r'([\w:-]+)\s*=\s*"([^"]*)"')
SVG_TAG_RE = re.compile(r"<(?P<tag>path|rect|svg)\b(?P<attrs>[^>]*?)/?>", re.S)
TINT_RE = re.compile(r'\s+android:tint="[^"]*"')
FILL_WHITE = 'android:fillColor="@android:color/white"'
DEFAULT_COLOR = "#FF000000"
CACHE_DIR = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "fetch-material-icons"
CACHE_TTL = 7 * 86400  # 元数据缓存一周


def build_url(name: str, style: str, variant: str, size: int, fmt: str) -> str:
    return CDN.format(style=style, name=name, variant=variant, size=size, ext=fmt)


def make_opener(proxy: str | None) -> urllib.request.OpenerDirector:
    handler = urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {})
    return urllib.request.build_opener(handler)


def http_get(opener, url: str, timeout: int = 90, attempts: int = 3) -> bytes:
    """GET，失败重试；代理偶发 SSL EOF 时靠重试兜住。"""
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) fetch-material-icons/2.0",
                    "Accept-Encoding": "identity",
                },
            )
            with opener.open(req, timeout=timeout) as resp:
                payload = resp.read()
            if payload[:2] == b"\x1f\x8b":  # 万一服务器还是 gzip 了
                payload = gzip.decompress(payload)
            return payload
        except urllib.error.HTTPError:
            raise  # 404 之类重试没意义
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last = exc
            if attempt + 1 < attempts:
                time.sleep(1.5 * (attempt + 1))
    raise last  # type: ignore[misc]


def decode_text(payload: bytes) -> str:
    return payload.decode("utf-8", errors="replace")


def sanitize_android_xml(xml: str, color: str, keep_tint: bool) -> str:
    """官方 Android XML 后处理：去掉 tint（纯 Compose 项目里会链接失败），改填充色。"""
    if not keep_tint:
        xml = TINT_RE.sub("", xml)
    if color:
        xml = xml.replace(FILL_WHITE, f'android:fillColor="{color}"')
    return xml


def parse_attrs(raw: str) -> dict[str, str]:
    return {k: html.unescape(v) for k, v in SVG_ATTR_RE.findall(raw)}


def normalize_color(value: str | None, default: str) -> str:
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
    for match in SVG_TAG_RE.finditer(svg):
        if match.group("tag") != "path":
            continue  # <rect fill="none"> 之类的背景块直接丢掉
        attrs = parse_attrs(match.group("attrs"))
        data = attrs.get("d", "").strip()
        if not data or attrs.get("fill", "").strip().lower() == "none":
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
        out.append("    <group")
        if min_x != 0:
            out.append(f'        android:translateX="{dp(-min_x)}"')
        if min_y != 0:
            out.append(f'        android:translateY="{dp(-min_y)}"')
        out.append("        >")

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


def load_metadata(opener, path: str | None, no_cache: bool = False) -> dict:
    cache_file = CACHE_DIR / "icons-metadata.json"
    if path:
        raw = Path(path).read_text(encoding="utf-8")
    elif not no_cache and cache_file.exists() and time.time() - cache_file.stat().st_mtime < CACHE_TTL:
        raw = cache_file.read_text(encoding="utf-8")
        print(f"（使用元数据缓存：{cache_file}）", file=sys.stderr)
    else:
        print("正在下载图标元数据（约 5.8 MB，可能需要几十秒）…", file=sys.stderr)
        raw = decode_text(http_get(opener, METADATA, timeout=180))
        try:
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            cache_file.write_text(raw, encoding="utf-8")
        except OSError:
            pass  # 缓存写不了不影响主流程
    if raw.startswith(")]}'"):
        raw = raw.split("\n", 1)[1]
    return json.loads(raw)


def search_icons(metadata: dict, query: str, limit: int) -> int:
    needle = query.lower().strip()
    words = [w for w in re.split(r"[\s,]+", needle) if w]
    hits: list[tuple[int, dict]] = []
    for icon in metadata.get("icons", []):
        haystack = " ".join([icon.get("name", "")] + icon.get("tags", []) + icon.get("categories", [])).lower()
        if not all(w in haystack for w in words):
            continue
        score = 0
        if icon.get("name") == needle:
            score += 100
        elif needle in icon.get("name", ""):
            score += 40
        if needle in [t.lower() for t in icon.get("tags", [])]:
            score += 20
        hits.append((score, icon))

    hits.sort(key=lambda item: (-item[0], -item[1].get("popularity", 0), item[1]["name"]))
    if not hits:
        print(f"没有匹配 “{query}” 的图标", file=sys.stderr)
        return 1
    print(f"匹配 “{query}” 的图标（共 {len(hits)} 个，显示前 {min(limit, len(hits))} 个）：")
    for _, icon in hits[:limit]:
        cats = ", ".join(icon.get("categories", [])[:3])
        print(f"  {icon['name']:<28} {cats}")
    print("\n直接下载：python3 tools/fetch_material_icons.py <name...> [--proxy ...]")
    return 0


def read_names(args: argparse.Namespace) -> list[str]:
    names: list[str] = list(args.names)
    if args.file:
        for line in Path(args.file).read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                names.extend(line.split())
    seen: set[str] = set()
    ordered: list[str] = []
    for name in names:
        if name not in seen:
            seen.add(name)
            ordered.append(name)
    return ordered


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="下载 Material Symbols 图标并放进 Android 项目的 res/drawable",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("names", nargs="*", help="图标名，如 calendar_month")
    parser.add_argument("--file", help="从文件读取图标名（每行一个，# 注释）")
    parser.add_argument("--search", metavar="QUERY", help="搜索图标名（按名字/tag/分类），不下载图标")
    parser.add_argument("--search-limit", type=int, default=25, help="搜索结果条数，默认 25")
    parser.add_argument("--metadata", metavar="FILE", help="复用已下载的 icons 元数据 JSON")
    parser.add_argument("--no-cache", action="store_true", help="忽略元数据缓存，强制重新下载")
    parser.add_argument("--style", choices=STYLES, default="outlined", help="默认 outlined")
    parser.add_argument("--variant", default="default", help="default / fill1 / wght300 ...（默认 default）")
    parser.add_argument("--size", type=int, choices=SIZES, default=24, help="基准尺寸，默认 24")
    parser.add_argument("--format", choices=("xml", "svg"), default="xml",
                        help="xml = 官方 Android VectorDrawable（默认）；svg = 自行转换")
    parser.add_argument("--color", default=DEFAULT_COLOR, help=f"填充色，默认 {DEFAULT_COLOR}；设为空则不改")
    parser.add_argument("--keep-tint", action="store_true",
                        help="保留官方 XML 的 android:tint（纯 Compose 项目会链接失败）")
    parser.add_argument("--prefix", default="ic_", help="资源名前缀，默认 ic_")
    parser.add_argument("--out", default="app/src/main/res/drawable",
                        help="输出目录，默认 app/src/main/res/drawable")
    parser.add_argument("--proxy", default=None, help="HTTP(S) 代理，如 http://127.0.0.1:7890")
    parser.add_argument("--save-raw", metavar="DIR", help="顺便把 CDN 原始文件保存到该目录")
    parser.add_argument("--dry-run", action="store_true", help="只打印 URL，不下载")
    args = parser.parse_args(argv)

    opener = make_opener(args.proxy)

    if args.search:
        try:
            metadata = load_metadata(opener, args.metadata, args.no_cache)
        except (urllib.error.URLError, TimeoutError) as exc:
            print(f"下载元数据失败：{exc}（试试 --proxy http://127.0.0.1:7890）", file=sys.stderr)
            return 1
        return search_icons(metadata, args.search, args.search_limit)

    names = read_names(args)
    if not names:
        parser.error("没有指定图标名（位置参数、--file 或 --search）")

    bad = [n for n in names if not NAME_RE.match(n)]
    if bad:
        parser.error(f"图标名只能包含小写字母/数字/下划线: {', '.join(bad)}")

    out_dir = Path(args.out)
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        if args.save_raw:
            Path(args.save_raw).mkdir(parents=True, exist_ok=True)

    failures: list[tuple[str, str]] = []
    for name in names:
        url = build_url(name, args.style, args.variant, args.size, args.format)
        res_name = f"{args.prefix}{name}"
        if args.dry_run:
            print(f"{res_name}\t{url}")
            continue
        try:
            payload = http_get(opener, url, timeout=90)
            if args.save_raw:
                (Path(args.save_raw) / f"{name}.{args.format}").write_bytes(payload)
            text = decode_text(payload)
            if not text.lstrip().startswith(("<vector", "<svg")):
                raise ValueError("服务器返回的不是 XML/SVG（图标名拼错了？）")
            if args.format == "xml":
                xml = sanitize_android_xml(text, args.color, args.keep_tint)
            else:
                xml = svg_to_vector(text, args.size, res_name, args.color or DEFAULT_COLOR)
            target = out_dir / f"{res_name}.xml"
            target.write_text(xml, encoding="utf-8")
            print(f"✓ {res_name}.xml  ({target.stat().st_size} B)")
        except urllib.error.HTTPError as exc:
            failures.append((name, f"HTTP {exc.code}（图标名拼错了？或该 style/variant/size 不存在）"))
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
