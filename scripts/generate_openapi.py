from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

if __package__:
    from .verify_traceability import by_id, direct_cells, parse
else:
    from verify_traceability import by_id, direct_cells, parse


ENDPOINT = re.compile(r"^(GET|POST|PUT|PATCH|DELETE)\s+(.+)$")
PATH_PARAMETER = re.compile(r"\{([^}]+)}")
STATUS = re.compile(r"\b([1-5]\d{2})\b")


def code_values(node: object) -> list[str]:
    return [item.text() for item in node.descendants("code")]


def response_contract(status: str) -> dict[str, object]:
    response: dict[str, object] = {"description": "Successful response" if status.startswith("2") else "Problem response"}
    if not status.startswith("2"):
        response["content"] = {
            "application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}
        }
    return response


def build_openapi(root: Path) -> dict[str, object]:
    lld = parse(root / "docs" / "LLD.html")
    section = by_id(lld, "modules")
    if section is None:
        raise ValueError("LLD modules section is missing")

    paths: dict[str, dict[str, object]] = {}
    operation_ids: set[str] = set()
    endpoint_count = 0
    for row in section.descendants("tr"):
        cells = direct_cells(row)
        if len(cells) != 5 or (match := ENDPOINT.fullmatch(cells[0].text())) is None:
            continue
        endpoint_count += 1
        method, declared_path = match.groups()
        origin = "application"
        path = declared_path
        servers: list[dict[str, str]] | None = None
        if declared_path.startswith("https://"):
            parsed = urlsplit(declared_path)
            path = parsed.path
            origin = "files" if parsed.hostname == "files.example" else "external"
            servers = [{"url": f"https://{origin}.invalid"}]

        use_case_codes = code_values(cells[1])
        if len(use_case_codes) < 2:
            raise ValueError(f"use case contract is incomplete: {method} {declared_path}")
        operation_id, command_class = use_case_codes[:2]
        if operation_id in operation_ids:
            raise ValueError(f"duplicate operationId: {operation_id}")
        operation_ids.add(operation_id)

        contract_codes = code_values(cells[2])
        statuses = list(dict.fromkeys(STATUS.findall(cells[3].text())))
        if not statuses:
            raise ValueError(f"response status is missing: {method} {declared_path}")

        operation: dict[str, object] = {
            "operationId": operation_id,
            "summary": cells[1].text(),
            "tags": [operation_id.replace("Creator", "").replace("Client", "")],
            "parameters": [
                {
                    "name": parameter,
                    "in": "path",
                    "required": True,
                    "schema": {"type": "string"},
                }
                for parameter in PATH_PARAMETER.findall(path)
            ],
            "responses": {status: response_contract(status) for status in statuses},
            "security": [] if "匿名" in cells[2].text() or path == "/webhooks/stripe" else [{"sessionCookie": []}],
            "x-command-class": command_class,
            "x-application-contract": contract_codes[0] if contract_codes else "",
            "x-actor-contract": cells[2].text(),
            "x-origin": origin,
            "x-lld-notes": cells[4].text(),
        }
        if servers is not None:
            operation["servers"] = servers
        method_key = method.lower()
        if method_key in paths.setdefault(path, {}):
            raise ValueError(f"duplicate endpoint: {method} {path}")
        paths[path][method_key] = operation

    if endpoint_count == 0:
        raise ValueError("LLD endpoint rows are missing")

    return {
        "openapi": "3.1.1",
        "info": {
            "title": "Stage Accord API",
            "version": "1.0.0",
            "description": "Generated from the approved LLD HTTP operation ledger.",
        },
        "servers": [{"url": "https://application.invalid/api/v1"}],
        "paths": paths,
        "components": {
            "securitySchemes": {
                "sessionCookie": {"type": "apiKey", "in": "cookie", "name": "__Host-stage-accord-session"}
            },
            "schemas": {
                "Problem": {
                    "type": "object",
                    "required": ["type", "title", "status", "code", "correlationId"],
                    "properties": {
                        "type": {"type": "string", "format": "uri-reference"},
                        "title": {"type": "string"},
                        "status": {"type": "integer", "minimum": 400, "maximum": 599},
                        "detail": {"type": "string"},
                        "instance": {"type": "string", "format": "uri-reference"},
                        "code": {"type": "string"},
                        "correlationId": {"type": "string"},
                    },
                    "additionalProperties": True,
                }
            },
        },
        "x-stage-accord-source": "docs/LLD.html#modules",
        "x-stage-accord-operation-count": endpoint_count,
    }


def render_openapi(root: Path) -> str:
    return json.dumps(build_openapi(root), ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate or verify the OpenAPI operation contract.")
    parser.add_argument("--check", action="store_true", help="Fail when the committed contract differs.")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    destination = root / "contracts" / "openapi.json"
    expected = render_openapi(root)
    if args.check:
        if not destination.is_file() or destination.read_text(encoding="utf-8") != expected:
            print("OPENAPI FAILED: run python scripts/generate_openapi.py")
            return 1
        count = build_openapi(root)["x-stage-accord-operation-count"]
        print(f"OPENAPI PASSED: {count} operations are current.")
        return 0
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(expected, encoding="utf-8", newline="\n")
    print(f"OPENAPI GENERATED: {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
