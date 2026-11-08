#!/usr/bin/env bash
# Waits until the API reports status UP on its health endpoint.
# Flyway migrates before the API serves requests, so UP also means the database is ready.
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-60}"
POLL_INTERVAL_SECONDS=1
REQUEST_TIMEOUT_SECONDS=2

for tool in curl jq; do
  if ! command -v "$tool" >/dev/null; then
    echo "$tool is required but not installed." >&2
    exit 1
  fi
done

for ((elapsed = 0; elapsed < WAIT_TIMEOUT_SECONDS; elapsed += POLL_INTERVAL_SECONDS)); do
  status="$(curl -fsS --max-time "$REQUEST_TIMEOUT_SECONDS" "$API_URL/actuator/health" 2>/dev/null | jq -r '.status' 2>/dev/null || true)"
  if [[ "$status" == "UP" ]]; then
    echo "API is UP at $API_URL"
    exit 0
  fi
  sleep "$POLL_INTERVAL_SECONDS"
done

echo "The API at $API_URL did not report UP within ${WAIT_TIMEOUT_SECONDS}s." >&2
exit 1
