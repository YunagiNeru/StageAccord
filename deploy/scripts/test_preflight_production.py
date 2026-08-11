import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("preflight", ROOT / "deploy/scripts/preflight_production.py")
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class ProductionPreflightTest(unittest.TestCase):
    def setUp(self):
        self.manifest = preflight.load_json(ROOT / "deploy/config/production-manifest.json")
        self.values = preflight.load_env(ROOT / "deploy/config/production.env.example")
        for key, value in list(self.values.items()):
            self.values[key] = value.replace("__REQUIRED_APP_FQDN__", "app.example.com")
        self.values.update({
            "APP_PUBLIC_HOST":"app.example.com", "FILES_PUBLIC_HOST":"files.example.com", "WEBAUTHN_RP_ID":"app.example.com",
            "WEBAUTHN_ALLOWED_ORIGINS":"https://app.example.com", "DB_URL":"jdbc:postgresql://db.example.com:5432/app?sslmode=verify-full",
            "VALKEY_URL":"rediss://cache.example.com:6379", "S3_REGION":"ap-northeast-1", "S3_ENDPOINT":"https://s3.ap-northeast-1.amazonaws.com",
            "S3_QUARANTINE_BUCKET":"product-quarantine-a1", "S3_CLEAN_BUCKET":"product-clean-a1", "S3_PREVIEW_BUCKET":"product-preview-a1", "S3_DELIVERY_BUCKET":"product-delivery-a1",
            "CLAMAV_HOST":"clamav.example.com", "MAIL_HOST":"smtp.example.com", "MAIL_FROM_ADDRESS":"notice@example.com",
            "APP_IMAGE":"registry.example.com/app@sha256:"+"a"*64, "EDGE_IMAGE":"registry.example.com/edge@sha256:"+"b"*64
        })

    def test_valid_bypass_contract(self):
        preflight.validate_schema(self.manifest)
        preflight.validate_values(self.values, self.manifest, True)

    def test_unknown_scan_mode_is_rejected(self):
        self.values["MALWARE_SCAN_MODE"] = "automatic"
        with self.assertRaises(preflight.PreflightError): preflight.validate_values(self.values, self.manifest, True)

    def test_required_requires_clamav_host(self):
        self.values["MALWARE_SCAN_MODE"] = "required"; self.values["CLAMAV_HOST"] = ""
        with self.assertRaises(preflight.PreflightError): preflight.validate_values(self.values, self.manifest, True)

    def test_duplicate_buckets_are_rejected(self):
        self.values["S3_CLEAN_BUCKET"] = self.values["S3_QUARANTINE_BUCKET"]
        with self.assertRaises(preflight.PreflightError): preflight.validate_values(self.values, self.manifest, True)

    def test_unmanaged_key_is_rejected(self):
        self.values["UNMANAGED_SECRET"] = "must-not-be-accepted"
        with self.assertRaises(preflight.PreflightError): preflight.validate_values(self.values, self.manifest, True)


if __name__ == "__main__": unittest.main()
