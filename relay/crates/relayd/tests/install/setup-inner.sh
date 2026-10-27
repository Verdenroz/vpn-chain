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

apt-get update -qq
apt-get install -y -qq --no-install-recommends minisign >/dev/null

case "$(uname -m)" in
    x86_64) arch=amd64 ;;
    aarch64) arch=arm64 ;;
esac
asset="vpn-chain-relay-linux-$arch.tar.gz"
mkdir -p /release/pkg
cd /release

minisign -G -W -f -p good.pub -s good.key >/dev/null
minisign -G -W -f -p other.pub -s other.key >/dev/null
PUB="$(sed -n 2p good.pub)"

printf '#!/bin/sh\nprintf "%%s\\n" "$*" >/ran\n' >pkg/relayd
chmod +x pkg/relayd
tar -czf "$asset" -C pkg relayd
minisign -S -s good.key -m "$asset" >/dev/null

(cd /release && exec python3 -m http.server 8000 --bind 127.0.0.1 >/dev/null 2>&1) &
sleep 1
sed "s|^PUBLIC_KEY=.*|PUBLIC_KEY=\"$PUB\"|" /setup.sh >/pinned.sh
run() { VPN_CHAIN_BASE_URL=http://127.0.0.1:8000 bash /pinned.sh "$@"; }

rm -f /ran
run --name phone >/dev/null 2>&1
test "$(cat /ran)" = "install --name phone"
pass "a release signed with the pinned key runs relayd install with the arguments given"

rm -f /ran
printf 'x' >>"$asset"
no run >/dev/null 2>&1
no test -e /ran
pass "a tampered release aborts before relayd runs"

tar -czf "$asset" -C pkg relayd
minisign -S -s other.key -m "$asset" -x "$asset.minisig" >/dev/null
no run >/dev/null 2>&1
no test -e /ran
pass "a release signed with another key aborts before relayd runs"

rm "$asset.minisig"
no run >/dev/null 2>&1
no test -e /ran
pass "a release with no signature aborts before relayd runs"

no env VPN_CHAIN_BASE_URL=http://127.0.0.1:8000 bash /setup.sh >/dev/null 2>&1
no test -e /ran
pass "the setup.sh in the repo refuses to run until a release key is pinned"

printf 'setup-inner.sh: %d checks passed\n' "$PASS"
