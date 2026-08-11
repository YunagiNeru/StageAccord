#!/usr/bin/env python3
"""release前に設定契約とdigest固定を再検査する。"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CONFIG = Path("/etc/stageaccord/config/production.env")


def main() -> int:
    result = subprocess.run([
        sys.executable,
        str(ROOT / "deploy/scripts/preflight_production.py"),
        "--config", str(CONFIG),
    ], check=False)
    if result.returncode != 0:
        return result.returncode
    print("RELEASE ARTIFACT CHECK PASSED: configuration and digest pins are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
