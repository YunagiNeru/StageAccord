from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

if __package__:
    from .verify_traceability import REQUIREMENT_ID, by_id, direct_cells, parse, row_requirement_id
else:
    from verify_traceability import REQUIREMENT_ID, by_id, direct_cells, parse, row_requirement_id


IMPLEMENTATION_PHASES = {
    "FR-ACC": [3],
    "FR-ADM": [8],
    "FR-AGR": [5],
    "FR-APP": [7],
    "FR-AUD": [8],
    "FR-BIL": [8],
    "FR-CHK": [5, 6],
    "FR-COM": [7],
    "FR-DEL": [7],
    "FR-FIL": [6],
    "FR-NOT": [7],
    "FR-PRJ": [5],
    "FR-PRO": [4],
    "FR-REQ": [4],
    "FR-REV": [7],
    "FR-SCH": [5],
    "FR-SVC": [4],
    "FR-TSK": [5],
    "FR-UPD": [7],
    "FR-WF": [4],
    "NFR-ACC": [9],
    "NFR-AVL": [10],
    "NFR-BCP": [2, 3, 8, 10],
    "NFR-I18N": [5],
    "NFR-OBS": [1],
    "NFR-OPS": [1, 2],
    "NFR-PERF": [10],
    "NFR-PORT": [1],
    "NFR-PRV": [2, 6, 8, 10],
    "NFR-SEC": [2, 6, 8],
    "NFR-UX": [9],
}


def requirement_family(requirement_id: str) -> str:
    return requirement_id.rsplit("-", 1)[0]


def build_manifest(root: Path) -> dict[str, object]:
    requirements = parse(root / "docs" / "REQUIREMENTS.html")
    lld = parse(root / "docs" / "LLD.html")

    identifiers = [
        node.attrs["id"]
        for node in requirements.descendants("a")
        if "req-id" in node.attrs.get("class", "").split()
        and REQUIREMENT_ID.fullmatch(node.attrs.get("id", ""))
    ]
    trace_rows = {
        requirement_id: row
        for row in lld.descendants("tr")
        if (requirement_id := row_requirement_id(row))
        and row.attrs.get("id") == f"TRACE-{requirement_id}"
    }

    entries: list[dict[str, object]] = []
    for requirement_id in identifiers:
        row = trace_rows.get(requirement_id)
        if row is None:
            raise ValueError(f"LLD trace row is missing: {requirement_id}")
        cells = direct_cells(row)
        if len(cells) != 7:
            raise ValueError(f"LLD trace row must have seven cells: {requirement_id}")
        family = requirement_family(requirement_id)
        phases = IMPLEMENTATION_PHASES.get(family)
        if phases is None:
            raise ValueError(f"implementation phase is missing: {family}")
        parent_test_id = cells[5].text()
        expected_test_id = f"T-{requirement_id}"
        if parent_test_id != expected_test_id:
            raise ValueError(f"parent test ID mismatch: {requirement_id}")
        entries.append(
            {
                "requirementId": requirement_id,
                "parentTestId": parent_test_id,
                "implementationPhases": phases,
                "verificationPerspectives": cells[6].text(),
            }
        )

    return {
        "schemaVersion": 1,
        "sources": [
            "docs/REQUIREMENTS.html",
            "docs/HLD.html",
            "docs/LLD.html",
            "docs/IMPLEMENTATION-PLAN.html",
        ],
        "requirements": entries,
    }


def render_manifest(root: Path) -> str:
    return json.dumps(build_manifest(root), ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate or verify the requirement test manifest.")
    parser.add_argument("--check", action="store_true", help="Fail when the committed manifest differs.")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    destination = root / "contracts" / "test-manifest.json"
    expected = render_manifest(root)
    if args.check:
        if not destination.is_file() or destination.read_text(encoding="utf-8") != expected:
            print("TEST MANIFEST FAILED: run python scripts/generate_test_manifest.py")
            return 1
        count = len(build_manifest(root)["requirements"])
        print(f"TEST MANIFEST PASSED: {count} requirements are current.")
        return 0

    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(expected, encoding="utf-8", newline="\n")
    print(f"TEST MANIFEST GENERATED: {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
