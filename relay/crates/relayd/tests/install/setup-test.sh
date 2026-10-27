#!/usr/bin/env bash
# Runs relay/packaging/setup.sh in a container against a release the test signs with a
# throwaway minisign key, and checks that anything the key did not sign aborts before relayd
# runs. The container is plain, not privileged, and gone afterwards.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../../.." && pwd)"
IMG=vpn-chain-test

docker build -q -t "$IMG" -f "$ROOT/server/tests/Dockerfile" "$ROOT/server/tests" >/dev/null
docker run --rm -v "$ROOT/relay/packaging/setup.sh:/setup.sh:ro" -v "$HERE/setup-inner.sh:/inner.sh:ro" \
    "$IMG" bash /inner.sh
