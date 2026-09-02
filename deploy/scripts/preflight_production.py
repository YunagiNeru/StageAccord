#!/usr/bin/env python3
"""単一のSpring設定と本番秘密ファイルを、秘密値を表示せず検証する。"""
from __future__ import annotations

import argparse
import ipaddress
import os
import re
import stat
import sys
from pathlib import Path
from urllib.parse import urlparse

PLACEHOLDER = re.compile(r"__REQUIRED|CHANGE_ME|TODO", re.IGNORECASE)
BUCKET = re.compile(r"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
HOST = re.compile(r"^(?=.{1,253}$)(?!.*\.\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$", re.I)
OCI = re.compile(r"^.+@sha256:[0-9a-f]{64}$")
REGION = re.compile(r"^[a-z]{2}(?:-gov)?-[a-z]+-\d$")
DURATION = re.compile(r"^[1-9]\d*(?:ms|s|m|h)$")
INLINE_SECRET = re.compile(r"(?i)(sk_(?:live|test)_|AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY)")
EXPECTED_PROFILES = {"app", "worker", "local", "production"}
LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1", "0.0.0.0"}

SECRET_BINDINGS = {
    "stage-accord.object-storage.application.access-key-id": "s3-application-access-key-id",
    "stage-accord.object-storage.application.secret-access-key": "s3-application-secret-access-key",
    "stage-accord.billing.stripe.api-key": "stripe-api-key",
    "stage-accord.billing.stripe.webhook-secret": "stripe-webhook-secret",
    "stage-accord.mail.ses.access-key-id": "ses-access-key-id",
    "stage-accord.mail.ses.secret-access-key": "ses-secret-access-key",
    "stage-accord.security.session-hmac-key": "session-hmac-key",
    "stage-accord.security.csrf-hmac-key": "csrf-hmac-key",
    "stage-accord.database.username": "db-username",
    "stage-accord.database.password": "db-password",
    "stage-accord.object-storage.worker.access-key-id": "s3-worker-access-key-id",
    "stage-accord.object-storage.worker.secret-access-key": "s3-worker-secret-access-key",
    "stage-accord.security.field-encryption-key": "field-encryption-key",
}

PRODUCTION_SECRET_PATHS = {
    "stage-accord.secrets.db-username-file": ("application", "db-username", 1),
    "stage-accord.secrets.db-password-file": ("application", "db-password", 16),
    "stage-accord.secrets.valkey-username-file": ("application", "valkey-username", 1),
    "stage-accord.secrets.valkey-password-file": ("application", "valkey-password", 16),
    "stage-accord.secrets.s3-access-key-id-file": ("application", "s3-application-access-key-id", 16),
    "stage-accord.secrets.s3-secret-access-key-file": ("application", "s3-application-secret-access-key", 32),
    "stage-accord.secrets.s3-worker-access-key-id-file": ("worker", "s3-worker-access-key-id", 16),
    "stage-accord.secrets.s3-worker-secret-access-key-file": ("worker", "s3-worker-secret-access-key", 32),
    "stage-accord.secrets.stripe-api-key-file": ("application", "stripe-api-key", 16),
    "stage-accord.secrets.stripe-webhook-secret-file": ("application", "stripe-webhook-secret", 16),
    "stage-accord.secrets.mail-username-file": ("worker", "smtp-username", 1),
    "stage-accord.secrets.mail-password-file": ("worker", "smtp-password", 16),
    "stage-accord.secrets.session-hmac-key-file": ("application", "session-hmac-key", 32),
    "stage-accord.secrets.csrf-hmac-key-file": ("application", "csrf-hmac-key", 32),
    "stage-accord.secrets.field-encryption-key-file": ("application", "field-encryption-key", 32),
}


class PreflightError(RuntimeError):
    pass


def load_properties_documents(path: Path) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    documents: list[dict[str, str]] = [{}]
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if line == "#---":
            documents.append({})
            continue
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line:
            raise PreflightError(f"application.propertiesの{number}行目に=がありません。")
        name, value = line.split("=", 1)
        name = name.strip()
        if not name or name in documents[-1]:
            raise PreflightError(f"設定キーが空または同一区画で重複しています: {name or number}")
        documents[-1][name] = value.strip()

    common = documents[0]
    if "spring.config.activate.on-profile" in common:
        raise PreflightError("共通区画へprofile指定は置けません。")
    profiles: dict[str, dict[str, str]] = {}
    for document in documents[1:]:
        profile = document.get("spring.config.activate.on-profile", "").strip()
        if not profile or profile in profiles:
            raise PreflightError(f"profile区画が未指定または重複しています: {profile or '<empty>'}")
        profiles[profile] = document
    return common, profiles


def require(values: dict[str, str], name: str) -> str:
    value = values.get(name, "").strip()
    if not value or PLACEHOLDER.search(value):
        raise PreflightError(f"設定値が未確定です: {name}")
    return value


def validate_url(value: str, schemes: set[str], name: str) -> None:
    parsed = urlparse(value.removeprefix("jdbc:"))
    if parsed.scheme not in schemes or not parsed.hostname or parsed.username or parsed.password:
        raise PreflightError(f"URL形式または資格情報分離が不正です: {name}")


def validate_bucket(value: str, name: str) -> None:
    if not BUCKET.fullmatch(value):
        raise PreflightError(f"bucket名が不正です: {name}")
    try:
        ipaddress.ip_address(value)
    except ValueError:
        return
    raise PreflightError(f"bucket名をIP形式にできません: {name}")


def validate_secret_file(path: Path, minimum_bytes: int) -> None:
    if not path.is_file() or path.stat().st_size < minimum_bytes:
        raise PreflightError(f"秘密fileがないか短すぎます: {path}")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o027:
        raise PreflightError(f"秘密fileの権限が過剰です: {path}")


def validate_contract(common: dict[str, str], profiles: dict[str, dict[str, str]], schema_only: bool) -> None:
    if set(profiles) != EXPECTED_PROFILES:
        raise PreflightError(f"profile集合が不正です: {sorted(profiles)}")
    if common.get("spring.profiles.default") != "none":
        raise PreflightError("既定profileはnoneでなければなりません。")
    expected_common_import = "optional:configtree:/run/secrets/application/,optional:configtree:/run/secrets/worker/"
    if common.get("spring.config.import") != expected_common_import:
        raise PreflightError("本番configtree importが不正です。")
    expected_local_import = "optional:configtree:secrets/application/,optional:configtree:secrets/worker/"
    if profiles["local"].get("spring.config.import") != expected_local_import:
        raise PreflightError("local configtree importが不正です。")

    all_values = [common, *profiles.values()]
    for document in all_values:
        for name, value in document.items():
            if re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
                raise PreflightError(f"環境変数形式の旧設定キーを使用できません: {name}")
            if INLINE_SECRET.search(value):
                raise PreflightError(f"application.propertiesへ秘密値を直書きできません: {name}")
    for name, filename in SECRET_BINDINGS.items():
        if common.get(name) != "${" + filename + ":}":
            raise PreflightError(f"秘密値のconfigtree参照が不正です: {name}")

    production = common | profiles["production"]
    if production.get("stage-accord.environment") != "production":
        raise PreflightError("production profileと環境値が一致しません。")
    if profiles["local"].get("stage-accord.environment") != "local":
        raise PreflightError("local profileと環境値が一致しません。")

    region = require(production, "stage-accord.object-storage.region")
    if not REGION.fullmatch(region):
        raise PreflightError("S3 regionが不正です。")
    endpoint = require(production, "stage-accord.object-storage.endpoint")
    validate_url(endpoint, {"https"}, "stage-accord.object-storage.endpoint")
    if not schema_only and endpoint != f"https://s3.{region}.amazonaws.com":
        raise PreflightError("Amazon S3公式endpointとregionが一致しません。")

    bucket_names = [
        "stage-accord.object-storage.quarantine-bucket",
        "stage-accord.object-storage.clean-bucket",
        "stage-accord.object-storage.preview-bucket",
        "stage-accord.object-storage.delivery-bucket",
    ]
    buckets = [require(production, name) for name in bucket_names]
    for name, value in zip(bucket_names, buckets, strict=True):
        validate_bucket(value, name)
    if len(set(buckets)) != len(buckets):
        raise PreflightError("4用途bucketは相互に異なる必要があります。")

    mode = require(production, "stage-accord.malware-scan.mode")
    if mode not in {"required", "bypass"}:
        raise PreflightError("malware scan modeはrequiredまたはbypassです。")
    if mode == "required":
        scan_host = require(production, "stage-accord.malware-scan.host").lower()
        if scan_host in LOOPBACK_HOSTS or scan_host.endswith(".invalid"):
            raise PreflightError("required時のClamAV hostが不正です。")
    port = require(production, "stage-accord.malware-scan.port")
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise PreflightError("ClamAV portが不正です。")
    for name in ("stage-accord.malware-scan.connect-timeout", "stage-accord.malware-scan.read-timeout"):
        if not DURATION.fullmatch(require(production, name)):
            raise PreflightError(f"durationが不正です: {name}")

    rp_id = require(production, "stage-accord.webauthn.rp-id").lower()
    if not HOST.fullmatch(rp_id) or rp_id in LOOPBACK_HOSTS or rp_id.endswith((".local", ".localhost", ".invalid")):
        raise PreflightError("WebAuthn RP IDが公開hostではありません。")
    for origin in require(production, "stage-accord.webauthn.allowed-origins").split(","):
        parsed = urlparse(origin.strip())
        if parsed.scheme != "https" or parsed.hostname != rp_id or parsed.username or parsed.password or parsed.path not in ("", "/"):
            raise PreflightError("WebAuthn originがRP IDと一致しません。")

    database_url = require(production, "stage-accord.database.source-url")
    validate_url(database_url, {"postgres", "postgresql"}, "stage-accord.database.source-url")
    if not schema_only and "sslmode=verify-full" not in urlparse(database_url.removeprefix("jdbc:")).query.split("&"):
        raise PreflightError("本番DBはsslmode=verify-fullが必要です。")
    if not schema_only:
        validate_url(require(production, "stage-accord.valkey.url"), {"rediss"}, "stage-accord.valkey.url")

    for name, (consumer, filename, minimum_bytes) in PRODUCTION_SECRET_PATHS.items():
        expected = Path("/run/secrets") / consumer / filename
        actual = Path(require(production, name))
        if actual != expected:
            raise PreflightError(f"本番秘密file pathが不正です: {name}")
        if not schema_only:
            validate_secret_file(actual, minimum_bytes)

    if not schema_only:
        for name in ("APP_IMAGE", "EDGE_IMAGE"):
            if not OCI.fullmatch(os.environ.get(name, "")):
                raise PreflightError(f"OCI imageがdigest固定ではありません: {name}")
        stripe_file = Path(require(production, "stage-accord.secrets.stripe-api-key-file"))
        if not stripe_file.read_text(encoding="utf-8").strip().startswith("sk_live_"):
            raise PreflightError("本番Stripe keyがlive modeではありません。")


def validate_obsolete_files(root: Path) -> None:
    obsolete = [
        root / "backend/src/main/resources/application.yml",
        root / "backend/src/main/resources/application-app.yml",
        root / "backend/src/main/resources/application-worker.yml",
        root / "backend/src/main/resources/application-production.yml",
        root / "deploy/config/production.env.example",
        root / "deploy/config/production-manifest.json",
    ]
    existing = [str(path.relative_to(root)) for path in obsolete if path.exists()]
    if existing:
        raise PreflightError(f"廃止済み設定fileが残っています: {existing}")


def main() -> int:
    parser = argparse.ArgumentParser()
    root = Path(__file__).resolve().parents[2]
    parser.add_argument("--properties", type=Path, default=root / "backend/src/main/resources/application.properties")
    parser.add_argument("--schema-only", action="store_true")
    args = parser.parse_args()
    try:
        common, profiles = load_properties_documents(args.properties)
        validate_contract(common, profiles, args.schema_only)
        validate_obsolete_files(root)
    except (OSError, UnicodeError, PreflightError) as exc:
        print(f"PREFLIGHT FAILED: {exc}", file=sys.stderr)
        return 1
    print("PREFLIGHT PASSED: single Spring configuration contract is valid; secret values were not printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
