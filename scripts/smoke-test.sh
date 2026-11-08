#!/usr/bin/env bash
# Checks the running API against the original data: the portfolio of user 1.
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
USER_ID=1
EXPECTED_AVAILABLE_CASH=627500
EXPECTED_TOTAL_VALUE=889756

"$SCRIPT_DIR/wait-for-api.sh"

portfolio="$(curl -fsS "$API_URL/users/$USER_ID/portfolio")"

if jq -e --argjson cash "$EXPECTED_AVAILABLE_CASH" --argjson total "$EXPECTED_TOTAL_VALUE" \
  '.cash.available == $cash and .totalValue == $total' <<<"$portfolio" >/dev/null; then
  echo "Smoke test passed: user $USER_ID has cash $EXPECTED_AVAILABLE_CASH and total value $EXPECTED_TOTAL_VALUE."
else
  echo "Smoke test FAILED. Expected cash.available=$EXPECTED_AVAILABLE_CASH and totalValue=$EXPECTED_TOTAL_VALUE, got:" >&2
  jq '{cash: .cash.available, totalValue: .totalValue}' <<<"$portfolio" >&2
  exit 1
fi
