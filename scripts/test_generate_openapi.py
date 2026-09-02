from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.generate_openapi import build_openapi


class GenerateOpenApiTest(unittest.TestCase):
    def test_builds_operation_and_problem_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            (docs / "LLD.html").write_text(
                """
                <section id="modules"><table><tr>
                  <th><code>POST /workspaces/{workspaceId}/commands</code></th>
                  <td><code>RunCommand</code><code>GENERAL_COMMAND</code></td>
                  <td><code>api.RunCommand</code> owner</td>
                  <td><code>202</code><code>403 AUTHORIZATION_DENIED</code></td>
                  <td>監査 <code>PRIVATE_NO_STORE</code></td>
                </tr></table></section>
                """,
                encoding="utf-8",
            )

            contract = build_openapi(root)
            operation = contract["paths"]["/workspaces/{workspaceId}/commands"]["post"]

            self.assertEqual("RunCommand", operation["operationId"])
            self.assertEqual("GENERAL_COMMAND", operation["x-command-class"])
            self.assertEqual([{"sessionCookie": []}], operation["security"])
            self.assertEqual(["202", "403"], list(operation["responses"]))
            self.assertEqual("#/components/schemas/Problem", operation["responses"]["403"]["content"]["application/problem+json"]["schema"]["$ref"])


if __name__ == "__main__":
    unittest.main()
