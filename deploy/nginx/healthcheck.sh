#!/usr/bin/env sh
set -eu
public_host="${APP_PUBLIC_HOST:?APP_PUBLIC_HOST is required}"
/usr/bin/curl --fail --silent --show-error --max-time 4 \
  --resolve "${public_host}:8443:127.0.0.1" \
  --cacert /run/secrets/edge/tls-certificate \
  "https://${public_host}:8443/actuator/health/readiness" >/dev/null
