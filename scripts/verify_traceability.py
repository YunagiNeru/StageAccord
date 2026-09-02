from __future__ import annotations

import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote


REQUIREMENT_ID = re.compile(r"^(?:FR|NFR)-[A-Z]+-\d{3}$")


@dataclass
class Node:
    tag: str
    attrs: dict[str, str]
    parent: "Node | None" = None
    children: list["Node"] = field(default_factory=list)
    chunks: list[str] = field(default_factory=list)

    def descendants(self, tag: str | None = None):
        for child in self.children:
            if tag is None or child.tag == tag:
                yield child
            yield from child.descendants(tag)

    def text(self) -> str:
        values = list(self.chunks)
        for child in self.children:
            values.append(child.text())
        return " ".join(" ".join(values).split())


class Parser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = Node("document", {})
        self.current = self.root

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        node = Node(tag, {key: value or "" for key, value in attrs}, self.current)
        self.current.children.append(node)
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}:
            self.current = node

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        if self.current.tag == tag:
            self.current = self.current.parent or self.root

    def handle_endtag(self, tag: str) -> None:
        node = self.current
        while node is not self.root and node.tag != tag:
            node = node.parent or self.root
        if node is not self.root:
            self.current = node.parent or self.root

    def handle_data(self, data: str) -> None:
        self.current.chunks.append(data)


def parse(path: Path) -> Node:
    parser = Parser()
    parser.feed(path.read_text(encoding="utf-8"))
    return parser.root


def by_id(root: Node, value: str) -> Node | None:
    return next((node for node in root.descendants() if node.attrs.get("id") == value), None)


def nearest(node: Node, tag: str) -> Node | None:
    current: Node | None = node
    while current is not None and current.tag != tag:
        current = current.parent
    return current


def direct_cells(row: Node) -> list[Node]:
    return [child for child in row.children if child.tag in {"th", "td"}]


def row_requirement_id(row: Node) -> str | None:
    for link in row.descendants("a"):
        fragment = link.attrs.get("href", "").partition("#")[2]
        if REQUIREMENT_ID.fullmatch(fragment):
            return fragment
    return None


def check_required_fields(label: str, rows: list[tuple[str, Node]], expected: int, errors: list[str]) -> None:
    for requirement_id, row in rows:
        cells = direct_cells(row)
        if len(cells) != expected:
            errors.append(f"{label} {requirement_id}: 必須欄数が{len(cells)}件です（期待値{expected}件）")
            continue
        empty = [str(index + 1) for index, cell in enumerate(cells) if not cell.text()]
        if empty:
            errors.append(f"{label} {requirement_id}: 必須欄{','.join(empty)}が空です")


def check_links(files: dict[str, tuple[Path, Node]], errors: list[str]) -> None:
    for name, (path, root) in files.items():
        for link in root.descendants("a"):
            href = unquote(link.attrs.get("href", ""))
            if not href or href.startswith(("http://", "https://", "mailto:")):
                continue
            target_name, separator, fragment = href.partition("#")
            if not separator or not fragment:
                continue
            target_path = (path.parent / target_name).resolve() if target_name else path.resolve()
            target = next((item for item in files.values() if item[0].resolve() == target_path), None)
            if target is None:
                if target_path.exists():
                    target = (target_path, parse(target_path))
                else:
                    errors.append(f"{name}: リンク先ファイルがありません: {href}")
                    continue
            if by_id(target[1], fragment) is None:
                errors.append(f"{name}: リンク先IDがありません: {href}")


def verify(root: Path) -> list[str]:
    paths = {
        "REQUIREMENTS": root / "docs" / "REQUIREMENTS.html",
        "HLD": root / "docs" / "HLD.html",
        "LLD": root / "docs" / "LLD.html",
    }
    errors: list[str] = []
    for label, path in paths.items():
        if not path.is_file():
            errors.append(f"{label}: ファイルがありません: {path}")
    if errors:
        return errors

    files = {label: (path, parse(path)) for label, path in paths.items()}
    requirements_root = files["REQUIREMENTS"][1]
    hld_root = files["HLD"][1]
    lld_root = files["LLD"][1]

    requirement_links = [
        node for node in requirements_root.descendants("a")
        if "req-id" in node.attrs.get("class", "").split() and REQUIREMENT_ID.fullmatch(node.attrs.get("id", ""))
    ]
    requirement_ids = [node.attrs["id"] for node in requirement_links]
    requirement_rows = [(node.attrs["id"], nearest(node, "tr")) for node in requirement_links]
    requirement_rows = [(item, row) for item, row in requirement_rows if row is not None]

    hld_scope = by_id(hld_root, "HLD-TRC-001")
    if hld_scope is None:
        errors.append("HLD: HLD-TRC-001がありません")
        hld_rows: list[tuple[str, Node]] = []
    else:
        hld_rows = [(item, row) for row in hld_scope.descendants("tr") if (item := row_requirement_id(row))]

    lld_rows = []
    for row in lld_root.descendants("tr"):
        row_id = row.attrs.get("id", "")
        if row_id.startswith("TRACE-") and REQUIREMENT_ID.fullmatch(row_id.removeprefix("TRACE-")):
            lld_rows.append((row_id.removeprefix("TRACE-"), row))

    collections = {
        "REQUIREMENTS": requirement_ids,
        "HLD": [item for item, _ in hld_rows],
        "LLD": [item for item, _ in lld_rows],
    }
    canonical = set(requirement_ids)
    for label, identifiers in collections.items():
        duplicates = sorted(item for item, count in Counter(identifiers).items() if count > 1)
        if duplicates:
            errors.append(f"{label}: 重複ID: {', '.join(duplicates)}")
        if label != "REQUIREMENTS":
            missing = sorted(canonical - set(identifiers))
            unknown = sorted(set(identifiers) - canonical)
            if missing:
                errors.append(f"{label}: 欠落ID: {', '.join(missing)}")
            if unknown:
                errors.append(f"{label}: 未知ID: {', '.join(unknown)}")

    check_required_fields("REQUIREMENTS", requirement_rows, 5, errors)
    check_required_fields("HLD", hld_rows, 9, errors)
    check_required_fields("LLD", lld_rows, 7, errors)
    for label, rows in {"HLD": hld_rows, "LLD": lld_rows}.items():
        for requirement_id, row in rows:
            if f"T-{requirement_id}" not in row.text():
                errors.append(f"{label} {requirement_id}: 親試験ID T-{requirement_id} がありません")

    check_links(files, errors)
    return sorted(set(errors))


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = verify(root)
    if errors:
        print("TRACEABILITY FAILED")
        for error in errors:
            print(f"- {error}")
        return 1
    requirements = parse(root / "docs" / "REQUIREMENTS.html")
    count = sum(
        1 for node in requirements.descendants("a")
        if "req-id" in node.attrs.get("class", "").split() and REQUIREMENT_ID.fullmatch(node.attrs.get("id", ""))
    )
    print(f"TRACEABILITY PASSED: {count} requirements; set differences, duplicates, broken links, and empty required fields are zero.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
