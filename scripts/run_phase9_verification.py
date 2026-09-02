from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / ".verification" / "phase-9-system" / "requirement-results.json"

SUITES = {
    "contract": ["pnpm", "verify:traceability"],
    "test-manifest": ["pnpm", "verify:test-manifest"],
    "openapi": ["pnpm", "verify:openapi"],
    "backend": ["mvn", "-f", "backend/pom.xml", "test"],
    "web": ["pnpm", "typecheck:web"],
    "web-unit": ["pnpm", "test:web"],
    "web-build": ["pnpm", "build:web"],
    "web-system": ["pnpm", "test:web-e2e"],
    "production-config": [sys.executable, "-m", "unittest", "deploy/scripts/test_preflight_production.py"],
}

PHASE_10_ONLY = {"NFR-PERF", "NFR-AVL"}


def family(requirement_id: str) -> str:
    return requirement_id.rsplit("-", 1)[0]


def evidence_suites(requirement_id: str) -> list[str]:
    group = family(requirement_id)
    if group in {"NFR-ACC", "NFR-UX"}:
        return ["web", "web-unit", "web-build", "web-system"]
    if group.startswith("NFR-"):
        return ["contract", "backend", "production-config"]
    return ["contract", "backend", "web-system"]


def build_results(manifest: dict[str, object], outcomes: dict[str, bool]) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    for item in manifest["requirements"]:  # type: ignore[index]
        requirement = dict(item)  # type: ignore[arg-type]
        requirement_id = str(requirement["requirementId"])
        phases = list(requirement["implementationPhases"])
        suites = evidence_suites(requirement_id)
        phase_10_only = family(requirement_id) in PHASE_10_ONLY
        passed = all(outcomes.get(suite, False) for suite in suites)
        if phase_10_only:
            status = "deferred_to_phase_10"
        elif passed:
            status = "passed_with_phase_10_acceptance_pending" if 10 in phases else "passed"
        else:
            status = "failed"
        results.append({
            "requirementId": requirement_id,
            "parentTestId": requirement["parentTestId"],
            "status": status,
            "evidenceSuites": suites,
            "verificationPerspectives": requirement["verificationPerspectives"],
        })
    return results


def resolve_command(command: list[str]) -> list[str]:
    executable = shutil.which(command[0])
    if executable is None:
        raise FileNotFoundError(f"実行ファイルがPATH上にありません: {command[0]}")
    return [executable, *command[1:]]


def main() -> int:
    manifest = json.loads((ROOT / "contracts" / "test-manifest.json").read_text(encoding="utf-8"))
    outcomes: dict[str, bool] = {}
    executions: list[dict[str, object]] = []
    for name, command in SUITES.items():
        print(f"PHASE 9 SUITE: {name}", flush=True)
        completed = subprocess.run(resolve_command(command), cwd=ROOT, check=False)
        outcomes[name] = completed.returncode == 0
        executions.append({"suite": name, "command": command, "exitCode": completed.returncode})

    results = build_results(manifest, outcomes)
    screenshots = sorted(path.name for path in OUTPUT.parent.glob("*.png"))
    payload = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "databaseIntegrationConfigured": bool(os.environ.get("STAGE_ACCORD_TEST_DB_URL")),
        "executions": executions,
        "screenshots": screenshots,
        "summary": {
            "total": len(results),
            "passed": sum(item["status"] == "passed" for item in results),
            "passedWithPhase10AcceptancePending": sum(
                item["status"] == "passed_with_phase_10_acceptance_pending" for item in results
            ),
            "deferredToPhase10": sum(item["status"] == "deferred_to_phase_10" for item in results),
            "failed": sum(item["status"] == "failed" for item in results),
        },
        "requirements": results,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PHASE 9 RESULT: {OUTPUT}")
    if len(results) != 106 or len(screenshots) < 27 or any(not passed for passed in outcomes.values()):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
