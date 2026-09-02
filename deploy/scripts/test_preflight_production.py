import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("preflight", ROOT / "deploy/scripts/preflight_production.py")
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class ProductionPreflightTest(unittest.TestCase):
    def setUp(self):
        self.common, self.profiles = preflight.load_properties_documents(
            ROOT / "backend/src/test/resources/application-test-fixture.properties"
        )

    def test_current_single_file_schema_contract(self):
        preflight.validate_contract(self.common, self.profiles, True)

    def test_unknown_scan_mode_is_rejected(self):
        self.common["stage-accord.malware-scan.mode"] = "automatic"
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_contract(self.common, self.profiles, True)

    def test_required_requires_clamav_host(self):
        self.common["stage-accord.malware-scan.mode"] = "required"
        self.common["stage-accord.malware-scan.host"] = ""
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_contract(self.common, self.profiles, True)

    def test_duplicate_buckets_are_rejected(self):
        self.common["stage-accord.object-storage.clean-bucket"] = self.common[
            "stage-accord.object-storage.quarantine-bucket"
        ]
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_contract(self.common, self.profiles, True)

    def test_profile_set_is_closed(self):
        self.profiles["staging"] = {"spring.config.activate.on-profile": "staging"}
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_contract(self.common, self.profiles, True)


if __name__ == "__main__":
    unittest.main()
