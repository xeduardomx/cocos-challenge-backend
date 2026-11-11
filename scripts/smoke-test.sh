#!/usr/bin/env bash
# Checks the running stack: the API against the original data (the portfolio of user 1), Prometheus
# scraping it, and Grafana with its dashboard and a data source that answers.
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
USER_ID=1
EXPECTED_AVAILABLE_CASH=627500
EXPECTED_TOTAL_VALUE=889756
REQUEST_TIMEOUT_SECONDS=2
# Prometheus marks a target up after its first scrape, and it scrapes every 15 seconds. Grafana
# also takes a while to start.
RETRY_TIMEOUT_SECONDS=30
RETRY_INTERVAL_SECONDS=1
PROMETHEUS_TARGET_JOB=api
DASHBOARD_SEARCH=Cocos
DASHBOARD_TITLE="Cocos challenge API"
PROMETHEUS_DATASOURCE_UID="${PROMETHEUS_DATASOURCE_UID:-prometheus}"

# Runs the command until it succeeds or the time is up.
retry() {
  local elapsed
  for ((elapsed = 0; elapsed < RETRY_TIMEOUT_SECONDS; elapsed += RETRY_INTERVAL_SECONDS)); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$RETRY_INTERVAL_SECONDS"
  done
  return 1
}

prometheus_target_is_up() {
  curl -fsS --max-time "$REQUEST_TIMEOUT_SECONDS" "$PROMETHEUS_URL/api/v1/targets" |
    jq -e --arg job "$PROMETHEUS_TARGET_JOB" \
      '.data.activeTargets | any(.labels.job == $job and .health == "up")'
}

grafana_has_the_dashboard() {
  curl -fsS --max-time "$REQUEST_TIMEOUT_SECONDS" "$GRAFANA_URL/api/search?query=$DASHBOARD_SEARCH" |
    jq -e --arg title "$DASHBOARD_TITLE" 'any(.title == $title)'
}

grafana_datasource_is_healthy() {
  curl -fsS --max-time "$REQUEST_TIMEOUT_SECONDS" \
    "$GRAFANA_URL/api/datasources/uid/$PROMETHEUS_DATASOURCE_UID/health" |
    jq -e '.status == "OK"'
}

"$SCRIPT_DIR/wait-for-api.sh"

portfolio="$(curl -fsS --max-time "$REQUEST_TIMEOUT_SECONDS" "$API_URL/users/$USER_ID/portfolio")"

if ! retry prometheus_target_is_up; then
  echo "Smoke test FAILED. Prometheus at $PROMETHEUS_URL does not show the target '$PROMETHEUS_TARGET_JOB' up within ${RETRY_TIMEOUT_SECONDS}s." >&2
  exit 1
fi
if ! retry grafana_has_the_dashboard; then
  echo "Smoke test FAILED. Grafana at $GRAFANA_URL does not have the dashboard '$DASHBOARD_TITLE' within ${RETRY_TIMEOUT_SECONDS}s." >&2
  exit 1
fi
if ! retry grafana_datasource_is_healthy; then
  echo "Smoke test FAILED. The Grafana data source '$PROMETHEUS_DATASOURCE_UID' at $GRAFANA_URL is not healthy within ${RETRY_TIMEOUT_SECONDS}s." >&2
  exit 1
fi

if jq -e --argjson cash "$EXPECTED_AVAILABLE_CASH" --argjson total "$EXPECTED_TOTAL_VALUE" \
  '.cash.available == $cash and .totalValue == $total' <<<"$portfolio" >/dev/null; then
  echo "Smoke test passed: Prometheus scrapes the API, Grafana has the dashboard and a healthy data source, and user $USER_ID has cash $EXPECTED_AVAILABLE_CASH and total value $EXPECTED_TOTAL_VALUE."
else
  echo "Smoke test FAILED. Expected cash.available=$EXPECTED_AVAILABLE_CASH and totalValue=$EXPECTED_TOTAL_VALUE, got:" >&2
  jq '{cash: .cash.available, totalValue: .totalValue}' <<<"$portfolio" >&2
  exit 1
fi
