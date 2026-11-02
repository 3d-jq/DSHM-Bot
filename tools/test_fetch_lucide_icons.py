#!/usr/bin/env python3
"""Lucide 图标转换的离线测试：用固定 SVG 文本验证解析，不联网。"""

from __future__ import annotations

import unittest

import fetch_lucide_icons as lucide

SAMPLE_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
  <circle cx="12" cy="7" r="4" />
</svg>"""


class LucideConversionTest(unittest.TestCase):
    def test_parses_paths_and_circles(self) -> None:
        paths = lucide.svg_to_paths(SAMPLE_SVG)
        self.assertEqual(2, len(paths))
        self.assertIn("M19 21v-2", paths[0])
        self.assertIn("a 4 4 0 1 0 8 0", paths[1])

    def test_vector_xml_is_well_formed(self) -> None:
        xml = lucide.vector_xml(lucide.svg_to_paths(SAMPLE_SVG))
        self.assertIn("<vector", xml)
        self.assertIn('android:strokeWidth="2"', xml)
        self.assertEqual(2, xml.count("<path"))

    def test_all_expected_icons_are_declared(self) -> None:
        for name in ("user", "search", "plus", "briefcase", "bot", "book-open", "code", "users", "sparkles", "arrow-left"):
            self.assertIn(name, lucide.ICONS)


if __name__ == "__main__":
    unittest.main()
