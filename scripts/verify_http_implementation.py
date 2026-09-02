from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "contracts" / "openapi.json"
JAVA_ROOT = ROOT / "backend" / "src" / "main" / "java"

CLASS_PATH = re.compile(r'@RequestMapping\("([^"\r\n]+)"\)[\s\S]*?public\s+final\s+class')
METHOD = re.compile(
    r'@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"\r\n]*)"\))?'
    r'[\s\S]{0,1200}?\bpublic\s+(?:[\w<>?,.\[\]\s]+)\s+(\w+)\s*\('
)


def contract_operations(path: Path = OPENAPI) -> dict[str, tuple[str, str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    return {
        operation["operationId"]: (method.upper(), route)
        for route, path_item in document["paths"].items()
        for method, operation in path_item.items()
        if isinstance(operation, dict) and "operationId" in operation
    }


def implemented_operations(root: Path = JAVA_ROOT) -> dict[str, tuple[str, str]]:
    implemented: dict[str, tuple[str, str]] = {}
    for path in root.rglob("*Controller.java"):
        source = path.read_text(encoding="utf-8")
        base_match = CLASS_PATH.search(source)
        base = base_match.group(1) if base_match else ""
        for match in METHOD.finditer(source):
            verb, suffix, method_name = match.groups()
            operation_id = method_name[0].upper() + method_name[1:]
            route = normalize_route(base + (suffix or ""))
            implemented[operation_id] = (verb.upper(), route)
    return implemented


def normalize_route(route: str) -> str:
    route = re.sub(r"/+", "/", route or "/")
    if route.startswith("/api/v1"):
        route = route[len("/api/v1"):] or "/"
    return route


def audit(contract: dict[str, tuple[str, str]], implemented: dict[str, tuple[str, str]]) -> list[str]:
    failures: list[str] = []
    for operation_id, expected in contract.items():
        actual = implemented.get(operation_id)
        if actual is None:
            failures.append(f"MISSING {operation_id} {expected[0]} {expected[1]}")
        elif actual != expected:
            failures.append(
                f"MISMATCH {operation_id} expected={expected[0]} {expected[1]} actual={actual[0]} {actual[1]}"
            )
    for operation_id in sorted(set(implemented) - set(contract)):
        failures.append(f"UNDECLARED {operation_id} {implemented[operation_id][0]} {implemented[operation_id][1]}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    contract = contract_operations()
    implemented = implemented_operations()
    failures = audit(contract, implemented)
    print(f"HTTP implementation coverage: {len(contract) - sum(line.startswith('MISSING') for line in failures)}/{len(contract)}")
    for failure in failures:
        print(failure)
    return 1 if args.check and failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
