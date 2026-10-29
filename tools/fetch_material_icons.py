#!/usr/bin/env python3
"""
Batch-download Material Symbols icons (official Android XML or raw SVG) into res/drawable.

Why this script exists
----------------------
Icons on fonts.google.com/icons can be downloaded one by one, but:
  1. that is one download per icon, which is painful for a whole set;
  2. the Android XML the site offers carries `android:tint="?attr/colorControlNormal"`,
     and a Compose-only project (no appcompat / material-components) then fails to link:
     error: resource attr/colorControlNormal (aka <pkg>:attr/colorControlNormal) not found.
  3. the file names end with `_px` and have to be renamed by hand.

The script hits Google's CDN directly (the same bytes a browser downloads, so sha256 matches) and
outputs Android XML with the tint already stripped, ready to drop in.

Usage
-----
    # Download (outlined / default / 24px, official Android XML)
    python3 tools/fetch_material_icons.py calendar_month schedule event_repeat

    # Go through a local proxy (clash listens on 7890 by default)
    python3 tools/fetch_material_icons.py calendar_month --proxy http://127.0.0.1:7890
    HTTPS_PROXY=http://127.0.0.1:7890 python3 tools/fetch_material_icons.py calendar_month

    # Other style / filled variant / 48px
    python3 tools/fetch_material_icons.py notifications --style rounded --variant fill1 --size 48

    # Read names from a file (one per line, # starts a comment)
    python3 tools/fetch_material_icons.py --file tools/icons.txt

    # Search icon names (downloads the official metadata, matches name / tag / category)
    python3 tools/fetch_material_icons.py --search night --proxy http://127.0.0.1:7890

    # Print URLs only / keep the raw file / emit SVG instead of the official XML
    python3 tools/fetch_material_icons.py alarm --dry-run
    python3 tools/fetch_material_icons.py alarm --save-raw /tmp/raw
    python3 tools/fetch_material_icons.py alarm --format svg

Output
------
Writes app/src/main/res/drawable/ic_<name>.xml by default; use it in Compose as
painterResource(R.drawable.ic_calendar_month) and colour it with Icon(tint = ...).

Name -> URL rules (verified)
----------------------------
  https://fonts.gstatic.com/s/i/short-term/release/
      materialsymbols{style}/{name}/{variant}/{size}px.{xml|svg}
  style   in outlined / rounded / sharp   (there is no "filled"; use variant=fill1)
  variant in default / fill1 / wght300 ...
  size    in 20 / 24 / 40 / 48
Metadata: https://fonts.google.com/metadata/icons?key=material_symbols

Icons are published by Google under Apache-2.0 and may ship with the app (keep the NOTICE credit).
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
CACHE_TTL = 7 * 86400  # cache metadata for a week


def build_url(name: str, style: str, variant: str, size: int, fmt: str) -> str:
    return CDN.format(style=style, name=name, variant=variant, size=size, ext=fmt)


def make_opener(proxy: str | None) -> urllib.request.OpenerDirector:
    handler = urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {})
    return urllib.request.build_opener(handler)


def http_get(opener, url: str, timeout: int = 90, attempts: int = 3) -> bytes:
    """GET with retries; a flaky proxy SSL EOF is absorbed by retrying."""
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
            if payload[:2] == b"\x1f\x8b":  # in case the server gzips the payload anyway
                payload = gzip.decompress(payload)
            return payload
        except urllib.error.HTTPError:
            raise  # retrying a 404 is pointless
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last = exc
            if attempt + 1 < attempts:
                time.sleep(1.5 * (attempt + 1))
    raise last  # type: ignore[misc]


def decode_text(payload: bytes) -> str:
    return payload.decode("utf-8", errors="replace")


def sanitize_android_xml(xml: str, color: str, keep_tint: bool) -> str:
    """Post-process the official XML: drop the tint (it fails to link in a Compose-only project) and set the fill colour."""
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
        raise ValueError("SVG has no viewBox; cannot determine the coordinate system")
    try:
        min_x, min_y, vb_w, vb_h = (float(v) for v in viewbox.group(1).replace(",", " ").split())
    except ValueError as exc:
        raise ValueError(f"cannot parse viewBox: {viewbox.group(1)}") from exc

    paths: list[dict[str, str]] = []
    for match in SVG_TAG_RE.finditer(svg):
        if match.group("tag") != "path":
            continue  # drop background blocks such as <rect fill="none">
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
        raise ValueError("no usable <path> data in the SVG")

    def dp(value: float) -> str:
        return f"{value:g}"

    out: list[str] = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {res_name}: Material Symbols, generated by tools/fetch_material_icons.py -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{dp(size_dp)}dp"',
        f'    android:height="{dp(size_dp)}dp"',
        f'    android:viewportWidth="{dp(vb_w)}"',
        f'    android:viewportHeight="{dp(vb_h)}">',
    ]

    # Android's viewport origin must be (0,0), so a negative-origin viewBox is offset with a group
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
        print(f"(using cached metadata: {cache_file})", file=sys.stderr)
    else:
        print("Downloading icon metadata (~5.8 MB, may take a few dozen seconds)...", file=sys.stderr)
        raw = decode_text(http_get(opener, METADATA, timeout=180))
        try:
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            cache_file.write_text(raw, encoding="utf-8")
        except OSError:
            pass  # a failed cache write is not fatal
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
        print(f'no icons match "{query}"', file=sys.stderr)
        return 1
    print(f'icons matching "{query}" ({len(hits)} total, showing the first {min(limit, len(hits))}):')
    for _, icon in hits[:limit]:
        cats = ", ".join(icon.get("categories", [])[:3])
        print(f"  {icon['name']:<28} {cats}")
    print("\nDownload directly: python3 tools/fetch_material_icons.py <name...> [--proxy ...]")
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
        description="Download Material Symbols icons into an Android project's res/drawable",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("names", nargs="*", help="icon names, e.g. calendar_month")
    parser.add_argument("--file", help="read icon names from a file (one per line, # comments)")
    parser.add_argument("--search", metavar="QUERY", help="search icon names (by name / tag / category) instead of downloading")
    parser.add_argument("--search-limit", type=int, default=25, help="number of search results, default 25")
    parser.add_argument("--metadata", metavar="FILE", help="reuse an already downloaded icons metadata JSON")
    parser.add_argument("--no-cache", action="store_true", help="ignore the metadata cache and download it again")
    parser.add_argument("--style", choices=STYLES, default="outlined", help="default: outlined")
    parser.add_argument("--variant", default="default", help="default / fill1 / wght300 ... (default: default)")
    parser.add_argument("--size", type=int, choices=SIZES, default=24, help="base size, default 24")
    parser.add_argument("--format", choices=("xml", "svg"), default="xml",
                        help="xml = official Android VectorDrawable (default); svg = converted locally")
    parser.add_argument("--color", default=DEFAULT_COLOR, help=f"fill colour, default {DEFAULT_COLOR}; pass an empty string to leave it untouched")
    parser.add_argument("--keep-tint", action="store_true",
                        help="keep the official XML's android:tint (fails to link in a Compose-only project)")
    parser.add_argument("--prefix", default="ic_", help="resource name prefix, default ic_")
    parser.add_argument("--out", default="app/src/main/res/drawable",
                        help="output directory, default app/src/main/res/drawable")
    parser.add_argument("--proxy", default=None, help="HTTP(S) proxy, e.g. http://127.0.0.1:7890")
    parser.add_argument("--save-raw", metavar="DIR", help="also save the raw CDN files into this directory")
    parser.add_argument("--dry-run", action="store_true", help="print URLs only, do not download")
    args = parser.parse_args(argv)

    opener = make_opener(args.proxy)

    if args.search:
        try:
            metadata = load_metadata(opener, args.metadata, args.no_cache)
        except (urllib.error.URLError, TimeoutError) as exc:
            print(f"failed to download metadata: {exc} (try --proxy http://127.0.0.1:7890)", file=sys.stderr)
            return 1
        return search_icons(metadata, args.search, args.search_limit)

    names = read_names(args)
    if not names:
        parser.error("no icon names given (positional args, --file or --search)")

    bad = [n for n in names if not NAME_RE.match(n)]
    if bad:
        parser.error(f"icon names may only contain lowercase letters, digits and underscores: {', '.join(bad)}")

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
                raise ValueError("the server did not return XML/SVG (typo in the icon name?)")
            if args.format == "xml":
                xml = sanitize_android_xml(text, args.color, args.keep_tint)
            else:
                xml = svg_to_vector(text, args.size, res_name, args.color or DEFAULT_COLOR)
            target = out_dir / f"{res_name}.xml"
            target.write_text(xml, encoding="utf-8")
            print(f"✓ {res_name}.xml  ({target.stat().st_size} B)")
        except urllib.error.HTTPError as exc:
            failures.append((name, f"HTTP {exc.code} (typo in the icon name? or that style/variant/size does not exist)"))
        except (urllib.error.URLError, TimeoutError) as exc:
            failures.append((name, f"network error: {exc} (try --proxy http://127.0.0.1:7890)"))
        except Exception as exc:  # noqa: BLE001 - one failing icon must not stop the others
            failures.append((name, str(exc)))

    if failures:
        print("\nFailed icons:", file=sys.stderr)
        for name, reason in failures:
            print(f"  ✗ {name}: {reason}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
