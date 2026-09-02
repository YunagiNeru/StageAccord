from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.generate_test_manifest import build_manifest


class GenerateTestManifestTest(unittest.TestCase):
    def test_builds_parent_test_contract_from_canonical_documents(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            (docs / "REQUIREMENTS.html").write_text(
                '<a class="req-id" id="FR-ACC-001">FR-ACC-001</a>', encoding="utf-8"
            )
            cells = "".join(
                [
                    '<th><a href="REQUIREMENTS.html#FR-ACC-001">FR-ACC-001</a></th>',
                    "<td>HLD-AUT-001</td>",
                    "<td>identityaccess</td>",
                    "<td>RegisterCreator</td>",
                    "<td>iam.account</td>",
                    "<td>T-FR-ACC-001</td>",
                    "<td>正常・権限拒否</td>",
                ]
            )
            (docs / "LLD.html").write_text(
                f'<table><tr id="TRACE-FR-ACC-001">{cells}</tr></table>', encoding="utf-8"
            )

            manifest = build_manifest(root)

            self.assertEqual(1, manifest["schemaVersion"])
            self.assertEqual(
                {
                    "requirementId": "FR-ACC-001",
                    "parentTestId": "T-FR-ACC-001",
                    "implementationPhases": [3],
                    "verificationPerspectives": "正常・権限拒否",
                },
                manifest["requirements"][0],
            )


if __name__ == "__main__":
    unittest.main()
