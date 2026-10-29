#!/usr/bin/env python3
"""
Generate adaptive icon layers (foreground / background / monochrome) and legacy bitmaps from a
Material Symbols vector.

Run it from the project root:

    python3 tools/make_app_icon.py app/src/main/res/drawable/ic_calendar_clock.xml

The script reads the pathData of the source vector and re-lays it out on the 108dp adaptive icon
canvas:

* `res/drawable/ic_launcher_foreground.xml`  white glyph, scaled and centred inside the safe zone
* `res/drawable/ic_launcher_monochrome.xml`  same shape in black, for Android 13 themed icons
* `res/drawable/ic_launcher_background.xml`  diagonal gradient background
* `res/mipmap-*/ic_launcher(.webp|_round.webp)`  bitmaps for API 24-25, optional

Tune the glyph with `--glyph-size` (default 51dp: target length of the glyph's longest edge) and the
background with `--from` / `--to`. The bitmaps need `inkscape` and ImageMagick's `magick`.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# Adaptive icon canvas: 108dp, with a 66dp-diameter circular safe zone.
CANVAS_DP = 108
RES = Path("app/src/main/res")
MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def read_glyph(vector_file: Path) -> tuple[str, tuple[float, float, float, float]]:
    """Returns (pathData, (minX, minY, maxX, maxY)); the bbox is estimated from absolute coordinates."""
    text = vector_file.read_text(encoding="utf-8")
    match = re.search(r'android:pathData="([^"]+)"', text)
    if not match:
        sys.exit(f"{vector_file} has no android:pathData")

    path_data = match.group(1)
    # Only absolute coordinates of M/L/Q/C/H/V/Z are considered; good enough for a bbox estimate.
    xs: list[float] = []
    ys: list[float] = []
    for numbers in re.findall(r"(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)", path_data):
        xs.append(float(numbers[0]))
        ys.append(float(numbers[1]))
    if not xs:
        sys.exit(f"{vector_file}: no coordinate pairs in pathData, cannot estimate a bbox")
    return path_data, (min(xs), min(ys), max(xs), max(ys))


def adaptive_foreground(path_data: str, bbox, glyph_size_dp: float, color: str) -> str:
    min_x, min_y, max_x, max_y = bbox
    width = max_x - min_x
    height = max_y - min_y
    scale = glyph_size_dp / max(width, height)
    center_x = (min_x + max_x) / 2
    center_y = (min_y + max_y) / 2
    translate_x = CANVAS_DP / 2 - center_x * scale
    translate_y = CANVAS_DP / 2 - center_y * scale
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{CANVAS_DP}dp"
    android:height="{CANVAS_DP}dp"
    android:viewportWidth="{CANVAS_DP}"
    android:viewportHeight="{CANVAS_DP}">
    <group
        android:scaleX="{scale:.6f}"
        android:scaleY="{scale:.6f}"
        android:translateX="{translate_x:.3f}"
        android:translateY="{translate_y:.3f}">
        <path
            android:fillColor="{color}"
            android:pathData="{path_data}" />
    </group>
</vector>
"""


def adaptive_background(start: str, end: str) -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{CANVAS_DP}dp"
    android:height="{CANVAS_DP}dp"
    android:viewportWidth="{CANVAS_DP}"
    android:viewportHeight="{CANVAS_DP}">
    <path android:pathData="M0,0h{CANVAS_DP}v{CANVAS_DP}h-{CANVAS_DP}z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="{CANVAS_DP}"
                android:endY="{CANVAS_DP}">
                <item android:offset="0" android:color="{start}" />
                <item android:offset="1" android:color="{end}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


def svg_color(argb: str) -> str:
    """Convert Android's #AARRGGBB to the #RRGGBB SVG understands (SVG 1.1 has no alpha hex)."""
    value = argb.lstrip("#")
    return "#" + (value[2:] if len(value) == 8 else value)


def legacy_svg(path_data: str, bbox, glyph_size_dp: float, start: str, end: str, round_icon: bool) -> str:
    start = svg_color(start)
    end = svg_color(end)
    size = 512
    min_x, min_y, max_x, max_y = bbox
    scale = size * glyph_size_dp / CANVAS_DP / max(max_x - min_x, max_y - min_y)
    translate_x = size / 2 - (min_x + max_x) / 2 * scale
    translate_y = size / 2 - (min_y + max_y) / 2 * scale
    shape = (
        f'<circle cx="{size / 2}" cy="{size / 2}" r="{size / 2}" fill="url(#bg)"/>'
        if round_icon
        else f'<rect x="0" y="0" width="{size}" height="{size}" rx="{size * 0.22:.1f}" ry="{size * 0.22:.1f}" fill="url(#bg)"/>'
    )
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="{start}"/>
      <stop offset="1" stop-color="{end}"/>
    </linearGradient>
  </defs>
  {shape}
  <g transform="translate({translate_x:.3f},{translate_y:.3f}) scale({scale:.6f})">
    <path fill="#FFFFFF" d="{path_data}"/>
  </g>
</svg>
"""


def render_mipmaps(path_data: str, bbox, glyph_size_dp: float, start: str, end: str) -> None:
    inkscape = shutil.which("inkscape")
    magick = shutil.which("magick") or shutil.which("convert")
    if not inkscape or not magick:
        print("skipping bitmaps: inkscape and ImageMagick's magick are required", file=sys.stderr)
        return
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        for round_icon in (False, True):
            svg = tmp_dir / ("round.svg" if round_icon else "square.svg")
            svg.write_text(
                legacy_svg(path_data, bbox, glyph_size_dp, start, end, round_icon),
                encoding="utf-8",
            )
            for folder, px in MIPMAP_SIZES.items():
                png = tmp_dir / f"{px}.png"
                subprocess.run(
                    [
                        inkscape, str(svg),
                        "--export-type=png",
                        f"--export-filename={png}",
                        f"--export-width={px}",
                        f"--export-height={px}",
                    ],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                name = "ic_launcher_round.webp" if round_icon else "ic_launcher.webp"
                target = RES / folder / name
                target.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run([magick, str(png), str(target)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="source vector (XML exported from Material Symbols)")
    parser.add_argument("--glyph-size", type=float, default=51, help="target length of the glyph's longest edge in dp, default 51")
    parser.add_argument("--from", dest="start", default="#FF5C7BBC", help="gradient start colour")
    parser.add_argument("--to", dest="end", default="#FF24375F", help="gradient end colour")
    parser.add_argument("--no-mipmaps", action="store_true", help="write the vector layers only, skip the legacy bitmaps")
    args = parser.parse_args()

    path_data, bbox = read_glyph(args.source)
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable/ic_launcher_foreground.xml").write_text(
        adaptive_foreground(path_data, bbox, args.glyph_size, "#FFFFFFFF"), encoding="utf-8"
    )
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(
        adaptive_foreground(path_data, bbox, args.glyph_size, "#FF000000"), encoding="utf-8"
    )
    (RES / "drawable/ic_launcher_background.xml").write_text(
        adaptive_background(args.start, args.end), encoding="utf-8"
    )
    print("Wrote foreground / monochrome / background vectors")

    if not args.no_mipmaps:
        render_mipmaps(path_data, bbox, args.glyph_size, args.start, args.end)
        print("Generated " + ", ".join(MIPMAP_SIZES))


if __name__ == "__main__":
    main()
