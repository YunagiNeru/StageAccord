#!/usr/bin/env sh
set -eu

management_address="${MANAGEMENT_LISTEN_ADDRESS:?MANAGEMENT_LISTEN_ADDRESS is required}"
secret_root="${APP_SECRETS_DIRECTORY:?APP_SECRETS_DIRECTORY is required}"
healthcheck_certificate="${HEALTHCHECK_CLIENT_CERTIFICATE_FILE:?HEALTHCHECK_CLIENT_CERTIFICATE_FILE is required}"
healthcheck_private_key="${HEALTHCHECK_CLIENT_PRIVATE_KEY_FILE:?HEALTHCHECK_CLIENT_PRIVATE_KEY_FILE is required}"

exec /usr/bin/curl \
  --fail \
  --silent \
  --show-error \
  --max-time 4 \
  --cacert "${secret_root}/tls.management-ca.crt" \
  --cert "${healthcheck_certificate}" \
  --key "${healthcheck_private_key}" \
  "https://${management_address}:8081/actuator/health/readiness"
