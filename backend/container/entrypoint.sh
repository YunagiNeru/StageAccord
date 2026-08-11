#!/usr/bin/env sh
set -eu

role="${1:-}"
case "${role}" in
  application)
    artifact=/opt/stageaccord/application.jar
    ;;
  worker)
    artifact=/opt/stageaccord/worker.jar
    ;;
  *)
    echo "runtime role must be application or worker" >&2
    exit 2
    ;;
esac

if [ "${APP_ENVIRONMENT:-}" != "production" ]; then
  echo "production image refuses non-production environment" >&2
  exit 1
fi
if [ ! -d /run/secrets/application ]; then
  echo "application secret directory is unavailable" >&2
  exit 1
fi
if [ "${role}" = "worker" ] && [ ! -d /run/secrets/worker ]; then
  echo "worker secret directory is unavailable" >&2
  exit 1
fi

exec java \
  -XX:+ExitOnOutOfMemoryError \
  -XX:MaxRAMPercentage=75.0 \
  -Dfile.encoding=UTF-8 \
  -Djava.io.tmpdir=/tmp \
  -Duser.timezone=UTC \
  -jar "${artifact}"
