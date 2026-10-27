#!/usr/bin/env bash
# The half of setup-test.sh that runs in the container.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
PASS=0
pass() { PASS=$((PASS + 1)); printf 'PASS: %s\n' "$*"; }
no() {
    if "$@"; then
        echo "unexpectedly succeeded: $*" >&2
        return 1
    fi
}

case "$(uname -m)" in
    x86_64) arch=amd64 ;;
    aarch64) arch=arm64 ;;
esac
asset="vpn-chain-relay-linux-$arch.tar.gz"
mkdir -p /release/pkg
cd /release

printf '#!/bin/sh\nprintf "%%s\\n" "$*" >/ran\n' >pkg/relayd
chmod +x pkg/relayd
tar -czf "$asset" -C pkg relayd
sha256sum "$asset" >"$asset.sha256"

(cd /release && exec python3 -m http.server 8000 --bind 127.0.0.1 >/dev/null 2>&1) &
sleep 1
run() { VPN_CHAIN_BASE_URL=http://127.0.0.1:8000 bash /setup.sh "$@"; }

rm -f /ran
run --name phone >/dev/null 2>&1
test "$(cat /ran)" = "install --name phone"
pass "a release matching its checksum runs relayd install with the arguments given"

rm -f /ran
printf 'x' >>"$asset"
no run >/dev/null 2>&1
no test -e /ran
pass "a changed release aborts before relayd runs"

tar -czf "$asset" -C pkg relayd
rm "$asset.sha256"
no run >/dev/null 2>&1
no test -e /ran
pass "a release with no checksum aborts before relayd runs"

printf 'setup-inner.sh: %d checks passed\n' "$PASS"
