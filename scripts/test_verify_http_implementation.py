import tempfile
import unittest
from pathlib import Path

from scripts.verify_http_implementation import audit, implemented_operations


class VerifyHttpImplementationTest(unittest.TestCase):
    def test_extracts_controller_method_and_reports_route_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "SampleController.java").write_text(
                '''
                @RequestMapping("/api/v1/widgets")
                public final class SampleController {
                    @PostMapping("/{widgetId}/runs")
                    public ResponseEntity<Void> runWidget(@PathVariable UUID widgetId) { return null; }
                }
                ''',
                encoding="utf-8",
            )
            implemented = implemented_operations(root)

        self.assertEqual({"RunWidget": ("POST", "/widgets/{widgetId}/runs")}, implemented)
        self.assertEqual([], audit(implemented, implemented))
        self.assertEqual(
            ["MISMATCH RunWidget expected=PUT /widgets/{widgetId}/runs actual=POST /widgets/{widgetId}/runs"],
            audit({"RunWidget": ("PUT", "/widgets/{widgetId}/runs")}, implemented),
        )


if __name__ == "__main__":
    unittest.main()
