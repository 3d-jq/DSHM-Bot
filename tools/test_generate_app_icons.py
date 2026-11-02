#!/usr/bin/env python3
"""图标生成脚本的测试：跑一遍到临时目录，校验尺寸、透明度与 adaptive 描述。"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from PIL import Image

import generate_app_icons as icons


def _open(path: Path) -> Image.Image:
    with Image.open(path) as image:
        image.load()
        return image.copy()


class GenerateAppIconsTest(unittest.TestCase):
    def test_generates_expected_assets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            res = Path(tmp) / "res"
            icons.generate(icons.DEFAULT_SOURCE, res)

            for density, size in icons.LEGACY_SIZES.items():
                with self.subTest(density=density):
                    square = _open(res / f"mipmap-{density}" / "ic_launcher.png")
                    self.assertEqual(square.size, (size, size))
                    self.assertEqual(square.mode, "RGBA")
                    # 美术自带圆角：四角透明、中心不透明；不再额外切角。
                    self.assertEqual(square.getpixel((0, 0))[3], 0)
                    self.assertEqual(square.getpixel((size // 2, size // 2))[3], 255)
                    square.close()

            for density, size in icons.ADAPTIVE_SIZES.items():
                with self.subTest(density=density):
                    foreground = _open(res / f"mipmap-{density}" / "ic_launcher_foreground.png")
                    self.assertEqual(foreground.size, (size, size))
                    self.assertEqual(foreground.mode, "RGBA")
                    self.assertEqual(foreground.getpixel((0, 0))[3], 0)
                    self.assertEqual(foreground.getpixel((size // 2, size // 2))[3], 255)
                    foreground.close()

                    monochrome = _open(res / f"mipmap-{density}" / "ic_launcher_monochrome.png")
                    self.assertEqual(monochrome.size, (size, size))
                    self.assertEqual(monochrome.mode, "RGBA")
                    self.assertEqual(monochrome.getpixel((0, 0))[3], 0)
                    self.assertGreater(sum(1 for px in monochrome.getdata() if px[3] > 0), size)
                    monochrome.close()

            xml = (res / "mipmap-anydpi" / "ic_launcher.xml").read_text(encoding="utf-8")
            self.assertIn("@color/ic_launcher_background", xml)
            self.assertIn("@mipmap/ic_launcher_foreground", xml)
            self.assertIn("@mipmap/ic_launcher_monochrome", xml)

    def test_keeps_art_corners_transparent(self) -> None:
        art = icons.load_art(icons.DEFAULT_SOURCE)
        width, height = art.size
        self.assertEqual(art.mode, "RGBA")
        self.assertEqual(art.getpixel((0, 0))[3], 0)
        self.assertEqual(art.getpixel((width - 1, 0))[3], 0)
        self.assertEqual(art.getpixel((0, height - 1))[3], 0)
        self.assertEqual(art.getpixel((width - 1, height - 1))[3], 0)
        self.assertEqual(art.getpixel((width // 2, height // 2))[3], 255)

    def test_adaptive_foreground_stays_inside_safe_zone(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            res = Path(tmp) / "res"
            icons.generate(icons.DEFAULT_SOURCE, res)
            for density, size in icons.ADAPTIVE_SIZES.items():
                with self.subTest(density=density):
                    foreground = _open(res / f"mipmap-{density}" / "ic_launcher_foreground.png")
                    left, top, right, bottom = _alpha_bbox(foreground)
                    width = right - left + 1
                    height = bottom - top + 1
                    # 美术要落在中间安全区，避免被启动器遮罩切掉角色。
                    self.assertLessEqual(width, round(size * 0.75))
                    self.assertLessEqual(height, round(size * 0.75))
                    self.assertGreaterEqual(left, round(size * 0.1))
                    self.assertGreaterEqual(top, round(size * 0.1))
                    foreground.close()


def _alpha_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    width, height = image.size
    left, top, right, bottom = width, height, -1, -1
    pixels = image.load()
    for y in range(height):
        for x in range(width):
            if pixels[x, y][3] > 0:
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
    if right < left:
        raise AssertionError("image has no opaque pixels")
    return left, top, right, bottom


if __name__ == "__main__":
    unittest.main()
