#!/usr/bin/env bash
# Runs the Postman collection with Newman (in Docker) against the API started by docker compose.
# The collection expects the original data, so run it on a fresh `docker compose up`.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
NEWMAN_IMAGE="postman/newman:6.1.3"
COMPOSE_NETWORK="cocos-challenge_default"
API_URL_IN_NETWORK="http://api:8080"

"$SCRIPT_DIR/wait-for-api.sh"

docker run --rm --network "$COMPOSE_NETWORK" \
  -v "$REPO_DIR/postman:/etc/newman:ro" \
  "$NEWMAN_IMAGE" run cocos-challenge.postman_collection.json \
  -e local.postman_environment.json \
  --env-var "baseUrl=$API_URL_IN_NETWORK"
