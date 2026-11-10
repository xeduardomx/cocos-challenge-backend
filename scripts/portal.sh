#!/usr/bin/env bash
# Serves or builds the documentation portal with Zensical, in Docker.
#   ./scripts/portal.sh         serves it on this machine and rebuilds it on every change
#   ./scripts/portal.sh build   writes the static site to site/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
ZENSICAL_IMAGE="zensical/zensical:0.1.0"
PORTAL_PORT="${PORTAL_PORT:-8000}"
CONTAINER_PORT=8000
COMMAND="${1:-serve}"

# With the user of this machine: otherwise the generated files would belong to root.
run_zensical() {
  docker run --rm --user "$(id -u):$(id -g)" -v "$REPO_DIR:/docs" "$@"
}

case "$COMMAND" in
  serve)
    run_zensical -p "127.0.0.1:$PORTAL_PORT:$CONTAINER_PORT" "$ZENSICAL_IMAGE"
    ;;
  build)
    run_zensical "$ZENSICAL_IMAGE" build --clean --strict
    ;;
  *)
    echo "Usage: $0 [serve|build]" >&2
    exit 2
    ;;
esac
