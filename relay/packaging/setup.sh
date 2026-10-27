#!/usr/bin/env bash
# Sets up a vpn-chain relay on this Debian or Ubuntu machine. Run as root:
#   curl -fsSL https://github.com/Verdenroz/vpn-chain/releases/latest/download/setup.sh | sudo bash
# It downloads the release for this CPU, checks its minisign signature against the key below,
# and only then runs `relayd install`. Arguments are passed on: setup.sh --name phone
set -euo pipefail

# The public half of the release signing key. CONTRIBUTING.md says how to make the pair.
PUBLIC_KEY="REPLACE_WITH_THE_RELEASE_PUBLIC_KEY"
BASE_URL="${VPN_CHAIN_BASE_URL:-https://github.com/Verdenroz/vpn-chain/releases/latest/download}"

die() { printf 'setup: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root"
case "$PUBLIC_KEY" in
    RW*) ;;
    *) die "this setup.sh has no release key pinned, so it cannot check what it downloads" ;;
esac
case "$(uname -m)" in
    x86_64) arch=amd64 ;;
    aarch64) arch=arm64 ;;
    *) die "unsupported CPU $(uname -m); amd64 and arm64 are built" ;;
esac

for tool in curl minisign; do
    command -v "$tool" >/dev/null || missing="${missing:-} $tool"
done
if [ -n "${missing:-}" ]; then
    command -v apt-get >/dev/null || die "needs${missing}; install them and run again"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq </dev/null
    apt-get install -y -qq --no-install-recommends ca-certificates curl minisign </dev/null
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
asset="vpn-chain-relay-linux-$arch.tar.gz"
curl -fsSL -o "$work/$asset" "$BASE_URL/$asset"
curl -fsSL -o "$work/$asset.minisig" "$BASE_URL/$asset.minisig"
minisign -Vq -P "$PUBLIC_KEY" -m "$work/$asset" -x "$work/$asset.minisig" \
    || die "the signature on $asset does not match; nothing was run"

mkdir "$work/bin"
tar -xzf "$work/$asset" -C "$work/bin" --no-same-owner
# Nothing may read the pipe the rest of this script is arriving on.
"$work/bin/relayd" install "$@" </dev/null
