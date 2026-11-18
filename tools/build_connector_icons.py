#!/usr/bin/env python3
"""把连接器的品牌图标（simple-icons 那套实心 SVG）转成 Android VectorDrawable。

和 `fetch_lucide_icons.py` 的区别：lucide 是**描边**图标（stroke），连接器品牌件是
**实心**图形（fill + fill-rule），所以要按填充转，颜色统一留黑、由 `Icon(tint=…)` 上色。

源文件在 `design/connector-icons/`（从 DeepSearch 的 `assets/icons/connectors/` 拿的
官方件），映射表是同一个目录下的 `map.json`（连接器 id → 文件名）。一个文件可以对应
多个 id（Cloudflare 和 Cloudflare Docs 同一个 mark），生成一份 drawable 就够。

用法：
  python tools/build_connector_icons.py           # 生成到 app/src/main/res/drawable/
  python tools/build_connector_icons.py --check   # 只校验，不写文件
"""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = PROJECT_ROOT / "design" / "connector-icons"
MAP_FILE = SOURCE_DIR / "map.json"
OUT_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res" / "drawable"
SVG_NS = "{http://www.w3.org/2000/svg}"

# 图标在设置列表里以 24dp 为基准；宽高比从 viewBox 来（Monday 是 256×156 的横条）。
BASE_SIZE_DP = 24


def _num(value: str | None, default: float = 0.0) -> float:
    if value is None or value == "":
        return default
    return float(value)


def _fmt(value: float) -> str:
    if abs(value - round(value)) < 1e-6:
        return str(int(round(value)))
    return f"{value:.3f}".rstrip("0").rstrip(".")


def _view_box(root: ET.Element) -> tuple[float, float, float, float]:
    raw = (root.attrib.get("viewBox") or "").replace(",", " ").split()
    if len(raw) == 4:
        return tuple(float(part) for part in raw)  # type: ignore[return-value]
    width = _num(root.attrib.get("width"), BASE_SIZE_DP)
    height = _num(root.attrib.get("height"), BASE_SIZE_DP)
    return 0.0, 0.0, width, height


def _style_value(element: ET.Element, name: str, inherited: str) -> str:
    """`fill` / `fill-rule` 这类画法属性：元素自己的 > 祖先的 > 默认；`style` 里的优先。"""
    value = element.attrib.get(name) or inherited
    style = element.attrib.get("style") or ""
    for part in style.split(";"):
        key, _, raw = part.partition(":")
        if key.strip() == name and raw.strip():
            value = raw.strip()
    return str(value)


def _fill_rule(element: ET.Element, inherited: str) -> str:
    value = _style_value(element, "fill-rule", inherited)
    return "evenOdd" if value.lower() == "evenodd" else "nonZero"


def _subpaths(element: ET.Element) -> list[str]:
    """一个图形元素 → 若干条 pathData（圆 / 矩形也折成路径）。"""
    tag = element.tag.replace(SVG_NS, "")
    if tag == "path":
        data = element.attrib.get("d", "")
        return [data] if data else []
    if tag == "circle":
        cx, cy = _num(element.attrib.get("cx")), _num(element.attrib.get("cy"))
        r = _num(element.attrib.get("r"))
        return [f"M {_fmt(cx - r)} {_fmt(cy)} a {_fmt(r)} {_fmt(r)} 0 1 0 {_fmt(2 * r)} 0 a {_fmt(r)} {_fmt(r)} 0 1 0 {_fmt(-2 * r)} 0"]
    if tag == "ellipse":
        cx, cy = _num(element.attrib.get("cx")), _num(element.attrib.get("cy"))
        rx, ry = _num(element.attrib.get("rx")), _num(element.attrib.get("ry"))
        return [f"M {_fmt(cx - rx)} {_fmt(cy)} a {_fmt(rx)} {_fmt(ry)} 0 1 0 {_fmt(2 * rx)} 0 a {_fmt(rx)} {_fmt(ry)} 0 1 0 {_fmt(-2 * rx)} 0"]
    if tag == "rect":
        x, y = _num(element.attrib.get("x")), _num(element.attrib.get("y"))
        width, height = _num(element.attrib.get("width")), _num(element.attrib.get("height"))
        rx = _num(element.attrib.get("rx")) or _num(element.attrib.get("ry"))
        if rx <= 0:
            return [f"M {_fmt(x)} {_fmt(y)} h {_fmt(width)} v {_fmt(height)} h {_fmt(-width)} Z"]
        return [
            f"M {_fmt(x + rx)} {_fmt(y)} h {_fmt(width - 2 * rx)} a {_fmt(rx)} {_fmt(rx)} 0 0 1 {_fmt(rx)} {_fmt(rx)} "
            f"v {_fmt(height - 2 * rx)} a {_fmt(rx)} {_fmt(rx)} 0 0 1 {_fmt(-rx)} {_fmt(rx)} "
            f"h {_fmt(-(width - 2 * rx))} a {_fmt(rx)} {_fmt(rx)} 0 0 1 {_fmt(-rx)} {_fmt(-rx)} "
            f"v {_fmt(-(height - 2 * rx))} a {_fmt(rx)} {_fmt(rx)} 0 0 1 {_fmt(rx)} {_fmt(-rx)} Z"
        ]
    if tag in ("polygon", "polyline"):
        coords = [float(part) for part in (element.attrib.get("points") or "").replace(",", " ").split()]
        pairs = list(zip(coords[0::2], coords[1::2]))
        if not pairs:
            return []
        data = f"M {_fmt(pairs[0][0])} {_fmt(pairs[0][1])}"
        data += "".join(f" L {_fmt(x)} {_fmt(y)}" for x, y in pairs[1:])
        return [data + (" Z" if tag == "polygon" else "")]
    return []


def svg_to_fill_paths(svg_text: str) -> tuple[tuple[float, float, float, float], list[tuple[str, str]]]:
    """SVG → (viewBox, [(pathData, fillType)])。只认实心图形，描边元素跳过。"""
    root = ET.fromstring(svg_text)
    view_box = _view_box(root)
    inherited_rule = _fill_rule(root, "nonzero")
    # 根节点上的 `fill="none"` 很常见（下载来的品牌件、lucide 都这么写），它只是
    # 「没有默认填充」，不代表图形不要填充 —— 所以不往下继承。真正判断是不是
    # 实心件看元素自己：有明确 fill 就画；只有 stroke 的是描边图标，跳过。
    inherited_fill = _style_value(root, "fill", "")
    if inherited_fill.lower() == "none":
        inherited_fill = ""
    inherited_stroke = _style_value(root, "stroke", "")
    paths: list[tuple[str, str]] = []
    for element in root.iter():
        if element is root:
            continue
        tag = element.tag.replace(SVG_NS, "")
        if tag not in ("path", "circle", "ellipse", "rect", "polygon", "polyline"):
            continue
        fill = _style_value(element, "fill", inherited_fill).strip().lower()
        stroke = _style_value(element, "stroke", inherited_stroke).strip().lower()
        if fill == "none":
            continue
        filled = fill != ""
        stroked = stroke not in ("", "none")
        if not filled and stroked:
            continue
        fill_type = _fill_rule(element, inherited_rule)
        for data in _subpaths(element):
            paths.append((data, fill_type))
    return view_box, paths


def drawable_name(file_name: str) -> str:
    """`monday-icon.svg` → `ic_connector_monday`（去掉 `-icon` 后缀，连字符换成下划线）。"""
    stem = re.sub(r"-icon$", "", Path(file_name).stem.lower())
    cleaned = re.sub(r"[^a-z0-9]+", "_", stem).strip("_")
    if not cleaned:
        raise ValueError(f"cannot derive a resource name from {file_name}")
    return f"ic_connector_{cleaned}"


def vector_xml(view_box: tuple[float, float, float, float], paths: list[tuple[str, str]], source: str) -> str:
    _, _, width, height = view_box
    ratio = height / width if width else 1.0
    height_dp = _fmt(round(BASE_SIZE_DP * ratio, 3))
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- Generated by tools/build_connector_icons.py from design/connector-icons/{source}.",
        "     官方品牌图形（单色）：颜色由使用处的 Icon(tint) 决定，不要手改。 -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{BASE_SIZE_DP}dp"',
        f'    android:height="{height_dp}dp"',
        f'    android:viewportWidth="{_fmt(width)}"',
        f'    android:viewportHeight="{_fmt(height)}">',
    ]
    for data, fill_type in paths:
        lines.append(
            "    <path"
            f' android:pathData="{data}"'
            ' android:fillColor="#FF000000"'
            f' android:fillType="{fill_type}" />'
        )
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def load_map(map_file: Path = MAP_FILE) -> dict[str, str]:
    return json.loads(map_file.read_text(encoding="utf-8"))


def generate(out_dir: Path = OUT_DIR, source_dir: Path = SOURCE_DIR, check: bool = False) -> list[Path]:
    mapping = load_map(source_dir / "map.json")
    written: list[Path] = []
    done: set[str] = set()
    for connector_id, file_name in sorted(mapping.items()):
        name = drawable_name(file_name)
        if name in done:
            continue
        svg_path = source_dir / file_name
        if not svg_path.is_file():
            raise FileNotFoundError(f"{connector_id}: missing {svg_path}")
        view_box, paths = svg_to_fill_paths(svg_path.read_text(encoding="utf-8"))
        if not paths:
            raise ValueError(f"{file_name} produced no filled paths")
        xml = vector_xml(view_box, paths, file_name)
        target = out_dir / f"{name}.xml"
        if check:
            existing = target.read_text(encoding="utf-8") if target.is_file() else None
            if existing != xml:
                raise SystemExit(f"{target} is out of date; run tools/build_connector_icons.py")
        else:
            out_dir.mkdir(parents=True, exist_ok=True)
            target.write_text(xml, encoding="utf-8")
        written.append(target)
        done.add(name)
    if not check:
        # 映射里删掉一个图标时，别把它生成的资源留在仓库里（UnusedResources 会当错误）。
        for stale in out_dir.glob("ic_connector_*.xml"):
            if stale.stem not in done:
                stale.unlink()
    return written


def main() -> None:
    parser = argparse.ArgumentParser(description="Build connector brand icons as Android VectorDrawables")
    parser.add_argument("--out", type=Path, default=OUT_DIR)
    parser.add_argument("--source", type=Path, default=SOURCE_DIR)
    parser.add_argument("--check", action="store_true", help="verify the committed drawables are up to date")
    args = parser.parse_args()
    written = generate(args.out, args.source, check=args.check)
    action = "checked" if args.check else "wrote"
    print(f"{action} {len(written)} drawables")


if __name__ == "__main__":
    main()
