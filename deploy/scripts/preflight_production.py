#!/usr/bin/env python3
"""本番設定を秘密値を表示せずに検証する。"""
from __future__ import annotations

import argparse
import ipaddress
import json
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


class PreflightError(RuntimeError):
    pass


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise PreflightError(f"設定行{number}に=がありません。")
        name, value = line.split("=", 1)
        if name in values:
            raise PreflightError(f"設定キーが重複しています: {name}")
        values[name] = value
    return values


def require(values: dict[str, str], name: str) -> str:
    value = values.get(name, "").strip()
    if not value or PLACEHOLDER.search(value):
        raise PreflightError(f"設定値が未確定です: {name}")
    return value


def validate_url(value: str, schemes: set[str], name: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme not in schemes or not parsed.hostname or parsed.username or parsed.password:
        raise PreflightError(f"URL形式が不正です: {name}")


def validate_kind(name: str, value: str, kind: str, expected: str | None, values: dict[str, str]) -> None:
    if expected is not None and value != expected:
        raise PreflightError(f"固定値と一致しません: {name}")
    if kind == "literal": return
    if kind == "public-host":
        if not HOST.fullmatch(value) or value.endswith((".local", ".localhost")): raise PreflightError(f"公開hostが不正です: {name}")
    elif kind == "https-origin-list":
        for origin in value.split(","):
            validate_url(origin.strip(), {"https"}, name)
            if urlparse(origin.strip()).path not in ("", "/"): raise PreflightError(f"originにpathを含められません: {name}")
    elif kind == "jdbc-postgresql-tls-url":
        if not value.startswith("jdbc:postgresql://") or "sslmode=verify-full" not in value: raise PreflightError(f"DB TLS検証が不足しています: {name}")
    elif kind == "rediss-url": validate_url(value, {"rediss"}, name)
    elif kind == "aws-region":
        if not REGION.fullmatch(value): raise PreflightError(f"AWS regionが不正です: {name}")
    elif kind == "amazon-s3-endpoint":
        validate_url(value, {"https"}, name)
        expected_endpoint = f"https://s3.{require(values, 'S3_REGION')}.amazonaws.com"
        if value != expected_endpoint: raise PreflightError("Amazon S3公式endpointとregionが一致しません。")
    elif kind == "bucket-name":
        if not BUCKET.fullmatch(value): raise PreflightError(f"bucket名が不正です: {name}")
        try: ipaddress.ip_address(value)
        except ValueError: pass
        else: raise PreflightError(f"bucket名をIP形式にできません: {name}")
    elif kind == "scan-mode":
        if value not in {"required", "bypass"}: raise PreflightError("MALWARE_SCAN_MODEはrequiredまたはbypassです。")
    elif kind == "conditional-host":
        if values.get("MALWARE_SCAN_MODE") == "required" and not (HOST.fullmatch(value) or _is_ip(value)): raise PreflightError("required時はCLAMAV_HOSTが必要です。")
    elif kind == "tcp-port":
        if not value.isdigit() or not 1 <= int(value) <= 65535: raise PreflightError(f"portが不正です: {name}")
    elif kind == "duration":
        if not DURATION.fullmatch(value): raise PreflightError(f"durationが不正です: {name}")
    elif kind == "positive-integer":
        if not value.isdigit() or int(value) <= 0: raise PreflightError(f"正整数ではありません: {name}")
    elif kind == "non-negative-integer":
        if not value.isdigit(): raise PreflightError(f"非負整数ではありません: {name}")
    elif kind == "email-address":
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", value): raise PreflightError(f"email形式が不正です: {name}")
    elif kind == "oci-digest":
        if not OCI.fullmatch(value): raise PreflightError(f"OCI imageがdigest固定ではありません: {name}")
    elif kind == "absolute-directory":
        if not value.startswith("/"): raise PreflightError(f"絶対directoryではありません: {name}")
    else: raise PreflightError(f"未知の検証kindです: {kind}")


def _is_ip(value: str) -> bool:
    try: ipaddress.ip_address(value); return True
    except ValueError: return False


def validate_schema(manifest: dict) -> None:
    if manifest.get("schemaVersion") != 2: raise PreflightError("manifest schemaVersionが不正です。")
    names = [item["name"] for item in manifest["configuration"]] + [item["variable"] for item in manifest["secretFiles"]]
    if len(names) != len(set(names)): raise PreflightError("manifestに重複キーがあります。")


def validate_template_keys(values: dict[str, str], manifest: dict) -> None:
    expected = {item["name"] for item in manifest["configuration"]} | {item["variable"] for item in manifest["secretFiles"]}
    if set(values) != expected:
        missing = expected - set(values); unknown = set(values) - expected
        raise PreflightError(f"template key集合が不一致です: missing={sorted(missing)}, unknown={sorted(unknown)}")


def validate_values(values: dict[str, str], manifest: dict, schema_only: bool) -> None:
    expected = {item["name"] for item in manifest["configuration"]} | {item["variable"] for item in manifest["secretFiles"]}
    unknown = set(values) - expected
    if unknown: raise PreflightError(f"未管理の設定キーがあります: {','.join(sorted(unknown))}")
    for item in manifest["configuration"]:
        name = item["name"]
        if item.get("when") and values.get(next(iter(item["when"]))) != next(iter(item["when"].values())):
            continue
        value = require(values, name)
        validate_kind(name, value, item["kind"], item.get("expected"), values)
    buckets = [require(values, name) for name in ("S3_QUARANTINE_BUCKET","S3_CLEAN_BUCKET","S3_PREVIEW_BUCKET","S3_DELIVERY_BUCKET")]
    if len(set(buckets)) != 4: raise PreflightError("4用途bucketは相互に異なる必要があります。")
    if require(values, "WEBAUTHN_RP_ID") != require(values, "APP_PUBLIC_HOST"): raise PreflightError("RP IDとAPP_PUBLIC_HOSTが一致しません。")
    for item in manifest["secretFiles"]:
        path_value = require(values, item["variable"])
        if not path_value.startswith("/") or path_value.rstrip("/").split("/")[-1] != item["file"]: raise PreflightError(f"秘密file pathが不正です: {item['variable']}")
        if not schema_only: validate_secret(Path(path_value), item["minimumBytes"])


def validate_secret(path: Path, minimum_bytes: int) -> None:
    if not path.is_file() or path.stat().st_size < minimum_bytes: raise PreflightError(f"秘密fileがないか短すぎます: {path}")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o007 or mode & 0o020: raise PreflightError(f"秘密fileの権限が過剰です: {path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    root = Path(__file__).resolve().parents[2]
    parser.add_argument("--config", type=Path, default=Path("/etc/stageaccord/config/production.env"))
    parser.add_argument("--manifest", type=Path, default=root / "deploy/config/production-manifest.json")
    parser.add_argument("--schema-only", action="store_true")
    args = parser.parse_args()
    try:
        manifest = load_json(args.manifest); validate_schema(manifest)
        config = args.config if args.config.exists() else root / "deploy/config/production.env.example"
        values = load_env(config)
        if args.schema_only:
            validate_template_keys(values, manifest)
        else:
            validate_values(values, manifest, False)
    except (OSError, json.JSONDecodeError, PreflightError) as exc:
        print(f"PREFLIGHT FAILED: {exc}", file=sys.stderr); return 1
    print("PREFLIGHT PASSED: configuration contract is valid; secret values were not printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
