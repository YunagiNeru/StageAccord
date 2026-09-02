from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.verify_traceability import verify


REQUIREMENT = "FR-ACC-001"


def requirement_row(identifier: str = REQUIREMENT, empty: bool = False) -> str:
    value = "" if empty else "要件"
    return f'<tr><td><a class="req-id" id="{identifier}" href="#{identifier}">{identifier}</a></td><td>{value}</td><td>P0</td><td>確定</td><td>受入</td></tr>'


def hld_row(identifier: str = REQUIREMENT, broken: bool = False, empty: bool = False) -> str:
    control = '<a href="#MISSING">HLD</a>' if broken else "HLD"
    value = "" if empty else "module"
    cells = [f'<th><a href="REQUIREMENTS.html#{identifier}">{identifier}</a></th>', f"<td>{control}</td>", f"<td>{value}</td>"]
    cells.extend("<td>値</td>" for _ in range(5))
    cells.append(f"<td>T-{identifier}</td>")
    return f"<tr>{''.join(cells)}</tr>"


def lld_row(identifier: str = REQUIREMENT) -> str:
    cells = [f'<th><a href="REQUIREMENTS.html#{identifier}">{identifier}</a></th>']
    cells.extend("<td>値</td>" for _ in range(5))
    cells.append(f"<td>T-{identifier}</td>")
    return f'<tr id="TRACE-{identifier}">{"".join(cells)}</tr>'


class TraceabilityTest(unittest.TestCase):
    def run_fixture(self, requirement_rows: str, hld_rows: str, lld_rows: str) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            (docs / "REQUIREMENTS.html").write_text(f"<html><body><table>{requirement_rows}</table></body></html>", encoding="utf-8")
            (docs / "HLD.html").write_text(f'<html><body><div id="HLD-TRC-001"><table>{hld_rows}</table></div></body></html>', encoding="utf-8")
            (docs / "LLD.html").write_text(f"<html><body><table>{lld_rows}</table></body></html>", encoding="utf-8")
            return verify(root)

    def test_valid_documents_pass(self) -> None:
        self.assertEqual([], self.run_fixture(requirement_row(), hld_row(), lld_row()))

    def test_failure_fixtures_are_rejected(self) -> None:
        fixtures = {
            "欠落": (requirement_row(), "", lld_row()),
            "未知": (requirement_row(), hld_row() + hld_row("FR-ACC-999"), lld_row()),
            "重複": (requirement_row() + requirement_row(), hld_row(), lld_row()),
            "リンク先ID": (requirement_row(), hld_row(broken=True), lld_row()),
            "必須欄": (requirement_row(), hld_row(empty=True), lld_row()),
        }
        for expected, values in fixtures.items():
            with self.subTest(expected=expected):
                self.assertIn(expected, "\n".join(self.run_fixture(*values)))


if __name__ == "__main__":
    unittest.main()
