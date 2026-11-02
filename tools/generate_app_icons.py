#!/usr/bin/env python3
"""从设计稿生成 Android 启动图标（legacy + adaptive icon）。

设计稿本身是「白底 + 自带圆角的方形」，外面一圈纯黑背景。这里只做两件事：
1. 把与画布边缘连通的纯黑背景抠掉（保留美术自带的圆角，不再二次切角）；
2. 按 Android 各密度输出 legacy 图标、adaptive 前景层与主题图标（monochrome）剪影。

输入：design/app-icon-source.png
输出：
  app/src/main/res/mipmap-*/ic_launcher.png             legacy（保留原图圆角）
  app/src/main/res/mipmap-*/ic_launcher_foreground.png   adaptive 前景层（满画布）
  app/src/main/res/mipmap-*/ic_launcher_monochrome.png   主题图标剪影
  app/src/main/res/mipmap-anydpi/ic_launcher.xml         adaptive 描述

用法：
  python tools/generate_app_icons.py
"""

from __future__ import annotations

import argparse
from collections import deque
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = PROJECT_ROOT / "design" / "app-icon-source.png"
DEFAULT_RES = PROJECT_ROOT / "app" / "src" / "main" / "res"
ART_EXPORT = PROJECT_ROOT / "design" / "app-icon-art.png"

LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# 与画布边缘连通的「近黑」像素视为背景；美术内部的深色描边不会与边缘连通，不会被误删。
BLACK_THRESHOLD = 32
# 主题图标剪影：比这更亮的像素视为留白（脸部高光、围裙等），不进入剪影。
MONOCHROME_LUMA_MAX = 200


def _is_background(pixel: tuple[int, int, int, int], threshold: int) -> bool:
    red, green, blue = pixel[0], pixel[1], pixel[2]
    return red <= threshold and green <= threshold and blue <= threshold


def load_art(source: Path) -> Image.Image:
    """抠掉外圈纯黑背景，返回带透明圆角的正方形美术图。"""
    image = Image.open(source).convert("RGBA")
    width, height = image.size
    pixels = image.load()
    visited = bytearray(width * height)
    queue: deque[tuple[int, int]] = deque()

    def enqueue(x: int, y: int) -> None:
        index = y * width + x
        if visited[index]:
            return
        if not _is_background(pixels[x, y], BLACK_THRESHOLD):
            return
        visited[index] = 1
        queue.append((x, y))

    for x in range(width):
        enqueue(x, 0)
        enqueue(x, height - 1)
    for y in range(height):
        enqueue(0, y)
        enqueue(width - 1, y)

    while queue:
        x, y = queue.popleft()
        pixels[x, y] = (0, 0, 0, 0)
        if x > 0:
            enqueue(x - 1, y)
        if x + 1 < width:
            enqueue(x + 1, y)
        if y > 0:
            enqueue(x, y - 1)
        if y + 1 < height:
            enqueue(x, y + 1)

    bbox = image.getbbox()
    if bbox is None:
        raise ValueError(f"没有在 {source} 里找到美术区域")
    art = image.crop(bbox)

    # 方形化：内容已经是方形，这里只防御源图比例不一致。
    side = max(art.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(art, ((side - art.width) // 2, (side - art.height) // 2))
    return canvas


# adaptive 前景层：Android 的 adaptive icon 只有中间 72/108 是可见区，
# 美术必须缩进安全区（约 66%），否则角色会被启动器遮罩切掉。
FOREGROUND_SCALE = 0.66


def monochrome_layer(art: Image.Image, size: int, scale: float = FOREGROUND_SCALE) -> Image.Image:
    """主题图标剪影：把美术里的深色结构收成单色形状，留白处透明。"""
    content = max(1, round(size * scale))
    resized = art.resize((content, content), Image.Resampling.LANCZOS)
    luma = resized.convert("L").point(lambda value: 255 if value < MONOCHROME_LUMA_MAX else 0)
    alpha = resized.getchannel("A")
    mask = ImageChops.multiply(luma, alpha)
    mask = mask.filter(ImageFilter.MaxFilter(3))
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - content) // 2
    layer.paste(mask, (offset, offset))
    return layer


def safe_zone_layer(art: Image.Image, size: int, scale: float = FOREGROUND_SCALE) -> Image.Image:
    """把美术缩进 adaptive 安全区并居中，四周留透明（背景层负责白色）。"""
    content = max(1, round(size * scale))
    scaled = art.resize((content, content), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - content) // 2
    canvas.paste(scaled, (offset, offset))
    return canvas


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def generate(source: Path, res: Path) -> None:
    art = load_art(source)
    save_png(art, ART_EXPORT)

    for density, size in LEGACY_SIZES.items():
        scaled = art.resize((size, size), Image.Resampling.LANCZOS)
        save_png(scaled, res / f"mipmap-{density}" / "ic_launcher.png")

    for density, size in ADAPTIVE_SIZES.items():
        directory = res / f"mipmap-{density}"
        save_png(safe_zone_layer(art, size), directory / "ic_launcher_foreground.png")
        save_png(monochrome_layer(art, size), directory / "ic_launcher_monochrome.png")

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        '</adaptive-icon>\n'
    )
    anydpi = res / "mipmap-anydpi"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(adaptive, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate Android launcher icons")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--res", type=Path, default=DEFAULT_RES)
    args = parser.parse_args()
    generate(args.source, args.res)
    print(f"icons written to {args.res}")


if __name__ == "__main__":
    main()
