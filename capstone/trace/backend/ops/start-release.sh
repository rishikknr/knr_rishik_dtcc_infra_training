#!/usr/bin/env bash
# Intended database/port/artifact must be supplied explicitly. Never migrates on startup.
set -euo pipefail
: "${TRACE_RELEASE_JAR:?Set TRACE_RELEASE_JAR to an immutable release artifact}"
: "${TRACE_DATABASE_URL:?Set TRACE_DATABASE_URL to the intended JDBC database URL}"
: "${TRACE_SERVER_PORT:?Set TRACE_SERVER_PORT to an unused application port}"
: "${TRACE_RUNTIME_PASSWORD:?Export the runtime password privately}"
if [[ "$TRACE_RELEASE_JAR" != /* || "$TRACE_RELEASE_JAR" == */target/* || ! -f "$TRACE_RELEASE_JAR" ]]; then
  printf 'Use an absolute immutable release JAR outside target/.\n' >&2; exit 1
fi
if [[ ! "$TRACE_SERVER_PORT" =~ ^[0-9]+$ ]] || (( TRACE_SERVER_PORT < 1024 || TRACE_SERVER_PORT > 65535 )); then
  printf 'Use a valid unprivileged application port.\n' >&2; exit 1
fi
if lsof -nP -iTCP:"$TRACE_SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'Port %s is occupied. Inspect and stop only the intended service before starting.\n' "$TRACE_SERVER_PORT" >&2; exit 1
fi
# Fail-fast compatibility and role checks run before application readiness.
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$TRACE_RELEASE_JAR" \
  "--spring.datasource.url=$TRACE_DATABASE_URL" --spring.flyway.enabled=false \
  --server.address=127.0.0.1 "--server.port=$TRACE_SERVER_PORT" "$@"
