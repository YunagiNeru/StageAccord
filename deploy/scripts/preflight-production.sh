#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_directory}/../.." && pwd)"
environment_file="${1:-/etc/stageaccord/config/production.env}"

cd -- "${project_root}"
exec python3 deploy/scripts/preflight_production.py --config "${environment_file}"
