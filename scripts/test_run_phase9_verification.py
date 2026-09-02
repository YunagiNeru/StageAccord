from __future__ import annotations

import json
import unittest
from pathlib import Path

from scripts.run_phase9_verification import PHASE_10_ONLY, SUITES, build_results, resolve_command


ROOT = Path(__file__).resolve().parents[1]


class Phase9VerificationTest(unittest.TestCase):

    def test_every_requirement_receives_an_honest_result(self) -> None:
        manifest = json.loads((ROOT / "contracts" / "test-manifest.json").read_text(encoding="utf-8"))
        results = build_results(manifest, {name: True for name in SUITES})

        self.assertEqual(106, len(results))
        self.assertEqual(106, len({item["requirementId"] for item in results}))
        self.assertEqual(
            PHASE_10_ONLY,
            {item["requirementId"].rsplit("-", 1)[0] for item in results if item["status"] == "deferred_to_phase_10"},
        )
        self.assertFalse(any(item["status"] == "failed" for item in results))

    def test_failed_evidence_suite_fails_affected_requirements(self) -> None:
        manifest = json.loads((ROOT / "contracts" / "test-manifest.json").read_text(encoding="utf-8"))
        outcomes = {name: True for name in SUITES}
        outcomes["web-system"] = False

        results = build_results(manifest, outcomes)

        self.assertEqual("failed", next(item["status"] for item in results if item["requirementId"] == "NFR-ACC-001"))
        self.assertEqual("deferred_to_phase_10", next(item["status"] for item in results if item["requirementId"] == "NFR-PERF-001"))

    def test_platform_executable_is_resolved_from_path(self) -> None:
        command = resolve_command(["python", "--version"])

        self.assertTrue(Path(command[0]).is_file())
        self.assertEqual("--version", command[1])


if __name__ == "__main__":
    unittest.main()
