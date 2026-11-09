#!/usr/bin/env bash
# Runs the API against the remote database of DATABASE_URL (from .env), instead of the local copy.
#   ./scripts/run-on-neon.sh             uses it as it is: nothing changes its structure
#   ./scripts/run-on-neon.sh --migrate   applies the pending migrations first, after asking
# Every order sent to this API is recorded in that database, so the smoke test, the Postman
# collection and the load test are not meant for it: they run against the local copy.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$REPO_DIR/.env"
API_IMAGE="cocos-challenge-api"
CONTAINER_NAME="cocos-challenge-api-remote"
API_PORT="${API_PORT:-8080}"
CONTAINER_PORT=8080
CONFIRMATION_WORD="migrate"
URL_PATTERN='^postgres(ql)?://([^:]+):([^@]+)@([^?]+)(\?(.*))?$'
MIGRATE=false

case "${1:-}" in
  "") ;;
  --migrate) MIGRATE=true ;;
  *)
    echo "Usage: $0 [--migrate]" >&2
    exit 2
    ;;
esac

# Read, never sourced: the URL has an unquoted & that the shell would act on, printing the password.
database_url="${DATABASE_URL:-}"
if [[ -z "$database_url" && -f "$ENV_FILE" ]]; then
  database_url="$(grep -m1 '^DATABASE_URL=' "$ENV_FILE" | cut -d= -f2- | tr -d "\"'")"
fi
if [[ -z "$database_url" ]]; then
  echo "DATABASE_URL is not set: copy .env.example to .env and fill it in." >&2
  exit 2
fi
if [[ ! "$database_url" =~ $URL_PATTERN ]]; then
  echo "DATABASE_URL does not look like postgresql://user:password@host/database." >&2
  exit 2
fi
username="${BASH_REMATCH[2]}"
password="${BASH_REMATCH[3]}"
host_and_database="${BASH_REMATCH[4]}"
options="${BASH_REMATCH[6]}"

if [[ "$MIGRATE" == true ]]; then
  echo "This applies the pending migrations to the remote database. It cannot be undone with a command."
  read -r -p "Type $CONFIRMATION_WORD to continue: " answer
  if [[ "$answer" != "$CONFIRMATION_WORD" ]]; then
    echo "Nothing was changed." >&2
    exit 1
  fi
  # Schema changes go straight to the database: Neon asks not to run them through its pooler.
  host_and_database="${host_and_database/-pooler./.}"
fi

# The API takes the address in JDBC format, with the user and the password apart (RFC, section 9).
export SPRING_DATASOURCE_URL="jdbc:postgresql://$host_and_database?${options/channel_binding=/channelBinding=}"
export SPRING_DATASOURCE_USERNAME="$username"
export SPRING_DATASOURCE_PASSWORD="$password"
export SPRING_FLYWAY_ENABLED="$MIGRATE"

cd "$REPO_DIR"
docker compose build api

docker run --rm --name "$CONTAINER_NAME" -p "127.0.0.1:$API_PORT:$CONTAINER_PORT" \
  -e SPRING_DATASOURCE_URL -e SPRING_DATASOURCE_USERNAME -e SPRING_DATASOURCE_PASSWORD \
  -e SPRING_FLYWAY_ENABLED \
  "$API_IMAGE"
