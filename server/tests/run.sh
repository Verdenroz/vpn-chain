#!/usr/bin/env bash
# Run the server-script suites inside a debian container with the pinned Xray.
# Usage: server/tests/run.sh [suite.sh ...]   (default: every suite-*.sh)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
XRAY_DIR="$(dirname "$("$HERE/fetch-xray.sh")")"

docker build -q -t vpn-chain-test -f "$HERE/Dockerfile" "$HERE" >/dev/null

[ $# -gt 0 ] || set -- "$HERE"/suite-*.sh
rc=0
for s in "$@"; do
    docker run --rm -v "$ROOT":/repo:ro -v "$XRAY_DIR":/opt/xray:ro vpn-chain-test \
        bash "/repo/server/tests/$(basename "$s")" || rc=1
done
exit "$rc"
