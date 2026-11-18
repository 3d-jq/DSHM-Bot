#!/usr/bin/env python3
"""连接器品牌图标转换的离线测试：用固定 SVG 文本验证解析与生成，不联网。

顺带守住仓库里的产物：`generate(check=True)` 必须和 `res/drawable/ic_connector_*.xml`
逐字节一致 —— 改了 `design/connector-icons/` 却忘了跑脚本时，这条会红。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import build_connector_icons as icons

SQUARE_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z" fill-rule="evenodd" />
  <circle cx="12" cy="12" r="4" />
</svg>"""

WIDE_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 156">
  <path fill="#f62b54" d="M10 10h100v100H10z" />
</svg>"""

# lucide 那种描边件：根上是 `fill="none"` + `stroke="currentColor"`，路径自己不带 fill。
STROKE_ONLY_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
  <path d="M4 4l16 16" />
</svg>"""

# 下载来的品牌件常见写法：根上 `fill="none"`，但图形本身是实心块（exa.svg 就是这样）。
ROOT_FILL_NONE_FILLED_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none">
  <path d="M2 2h20v20H2z" />
</svg>"""


class ConnectorIconConversionTest(unittest.TestCase):
    def test_reads_filled_shapes_and_even_odd(self) -> None:
        view_box, paths = icons.svg_to_fill_paths(SQUARE_SVG)
        self.assertEqual((0.0, 0.0, 24.0, 24.0), view_box)
        self.assertEqual(2, len(paths))
        self.assertEqual("evenOdd", paths[0][1])
        self.assertIn("M12 2a10 10", paths[0][0])
        # 圆也折成路径（品牌件里偶有圆点）。
        self.assertIn("a 4 4", paths[1][0])

    def test_skips_stroke_only_shapes(self) -> None:
        _, paths = icons.svg_to_fill_paths(STROKE_ONLY_SVG)
        self.assertEqual([], paths)

    def test_root_fill_none_still_keeps_filled_brand_marks(self) -> None:
        # 根上的 fill="none" 只是「没有默认填充」，不是「不要填充」。
        _, paths = icons.svg_to_fill_paths(ROOT_FILL_NONE_FILLED_SVG)
        self.assertEqual(1, len(paths))

    def test_vector_xml_keeps_the_aspect_ratio(self) -> None:
        view_box, paths = icons.svg_to_fill_paths(WIDE_SVG)
        xml = icons.vector_xml(view_box, paths, "wide.svg")
        # 256×156 的横条 → 24dp 宽、24*156/256 = 14.625dp 高。
        self.assertIn('android:width="24dp"', xml)
        self.assertIn('android:height="14.625dp"', xml)
        self.assertIn('android:viewportWidth="256"', xml)
        # 单色：色由使用处的 Icon(tint) 决定。
        self.assertIn('android:fillColor="#FF000000"', xml)
        self.assertEqual(1, xml.count("<path"))

    def test_drawable_names_are_resource_safe(self) -> None:
        self.assertEqual("ic_connector_monday", icons.drawable_name("monday-icon.svg"))
        self.assertEqual("ic_connector_mercado_libre", icons.drawable_name("mercado-libre.svg"))
        self.assertEqual("ic_connector_caldotcom", icons.drawable_name("caldotcom.svg"))
        with self.assertRaises(ValueError):
            icons.drawable_name("图标.svg")

    def test_generate_writes_every_mapped_drawable(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            out = Path(raw)
            written = icons.generate(out, icons.SOURCE_DIR)
            mapping = icons.load_map()
            expected = {icons.drawable_name(name) for name in mapping.values()}
            self.assertEqual(expected, {path.stem for path in written})
            for path in written:
                xml = path.read_text(encoding="utf-8")
                self.assertIn("<vector", xml)
                self.assertIn('android:fillColor="#FF000000"', xml)

    def test_committed_drawables_are_up_to_date(self) -> None:
        # 没跑脚本 / 手改了产物都会在这里红。
        icons.generate(icons.OUT_DIR, icons.SOURCE_DIR, check=True)

    def test_map_points_at_real_svg_files(self) -> None:
        mapping = icons.load_map()
        self.assertGreater(len(mapping), 20)
        for connector_id, file_name in mapping.items():
            self.assertTrue(
                (icons.SOURCE_DIR / file_name).is_file(),
                f"{connector_id} → {file_name} 不存在",
            )
        # 同一个文件可以对应多个 id（Cloudflare / Cloudflare Docs）。
        self.assertEqual(mapping["cloudflare"], mapping["cloudflare-docs"])

    def test_map_ids_are_lowercase_connector_ids(self) -> None:
        raw = json.loads((icons.SOURCE_DIR / "map.json").read_text(encoding="utf-8"))
        for connector_id in raw:
            self.assertEqual(connector_id, connector_id.lower())
            self.assertNotIn(" ", connector_id)


if __name__ == "__main__":
    unittest.main()
