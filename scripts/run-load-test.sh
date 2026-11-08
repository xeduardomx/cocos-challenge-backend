#!/usr/bin/env bash
# Measures the response time with volume (GEN-8, RFC 8.4): starts a clean stack, loads the volume
# data and runs k6 against it. Before taking the stack down, it prints how the orders ended and a
# few loose requests for the extreme user, who is outside the goal (A-4).
# Exits with the k6 exit code: 0 only if every threshold is met.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
API_URL="${API_URL:-http://localhost:8080}"
COMPOSE_NETWORK="cocos-challenge_default"
API_URL_IN_NETWORK="http://api:8080"
K6_IMAGE="grafana/k6:2.3.0"
SEED_FILE="$REPO_DIR/perf/seed-volume.sql"
K6_SCRIPT_IN_CONTAINER="/perf/load-test.js"
HEAVY_USER_ID=10005
EXTREME_USER_ID=10006
EXTREME_PORTFOLIO_REQUESTS=5
EXTREME_PORTFOLIO_TIMEOUT_SECONDS=30
# Forwarded to k6 only when set: see the constants at the top of perf/load-test.js.
K6_OVERRIDABLE_VARIABLES=(
  P95_LIMIT_MS
  REQUESTS_PER_SECOND
  DURATION_SECONDS
  WARMUP_ITERATIONS_PER_SECOND
  WARMUP_SECONDS
)

cd "$REPO_DIR"

psql_in_postgres() {
  docker compose exec -T postgres psql -U postgres -X "$@"
}

# Takes the stack down however the script ends: the data lives in memory.
trap 'docker compose down' EXIT

docker compose down
docker compose up --build -d
API_URL="$API_URL" "$SCRIPT_DIR/wait-for-api.sh"

echo "Loading the volume data..."
psql_in_postgres -q -v ON_ERROR_STOP=1 <"$SEED_FILE"
last_seeded_order_id="$(psql_in_postgres -At -c 'select max(id) from orders')"

k6_variables=()
for variable in "${K6_OVERRIDABLE_VARIABLES[@]}"; do
  k6_variables+=(-e "$variable")
done

echo "Running k6 (about 100 seconds with the default values)..."
k6_exit_code=0
docker run --rm --network "$COMPOSE_NETWORK" \
  -v "$REPO_DIR/perf:/perf:ro" \
  -e API_URL="$API_URL_IN_NETWORK" \
  "${k6_variables[@]}" \
  "$K6_IMAGE" run --no-usage-report --quiet "$K6_SCRIPT_IN_CONTAINER" || k6_exit_code=$?

echo "== Orders created by this run, by kind of user, type and final status (warm-up included)"
psql_in_postgres -c "
  select case userid when $HEAVY_USER_ID then 'heavy' else 'typical' end as user_kind,
         type, status, count(*) as orders
  from orders where id > $last_seeded_order_id
  group by 1, 2, 3 order by 1, 2, 3"

echo "== Portfolio of the extreme user ($EXTREME_PORTFOLIO_REQUESTS loose requests), seconds each"
for ((i = 1; i <= EXTREME_PORTFOLIO_REQUESTS; i++)); do
  # LC_NUMERIC: curl prints the decimal separator of the locale, and a comma would not read as a number.
  LC_NUMERIC=C curl -fsS --max-time "$EXTREME_PORTFOLIO_TIMEOUT_SECONDS" -o /dev/null -w '%{time_total}\n' \
    "$API_URL/users/$EXTREME_USER_ID/portfolio"
done

echo "k6 exit code: $k6_exit_code"
exit "$k6_exit_code"
