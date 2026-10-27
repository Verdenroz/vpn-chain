#!/usr/bin/env bash
# Sets up a vpn-chain relay on this Debian or Ubuntu machine. Run as root:
#   curl -fsSL https://github.com/Verdenroz/vpn-chain/releases/latest/download/setup.sh | sudo bash
# It downloads the release for this CPU, checks it against the SHA-256 published beside it,
# and only then runs `relayd install`. Arguments are passed on: setup.sh --name phone
set -euo pipefail

BASE_URL="${VPN_CHAIN_BASE_URL:-https://github.com/Verdenroz/vpn-chain/releases/latest/download}"

die() { printf 'setup: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root"
case "$(uname -m)" in
    x86_64) arch=amd64 ;;
    aarch64) arch=arm64 ;;
    *) die "unsupported CPU $(uname -m); amd64 and arm64 are built" ;;
esac

for tool in curl sha256sum; do
    command -v "$tool" >/dev/null || missing="${missing:-} $tool"
done
if [ -n "${missing:-}" ]; then
    command -v apt-get >/dev/null || die "needs${missing}; install them and run again"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq </dev/null
    apt-get install -y -qq --no-install-recommends ca-certificates curl coreutils </dev/null
fi

# /tmp is noexec on some hardened images, and relayd runs from here.
work="$(mktemp -d /var/lib/vpn-chain-setup.XXXXXX)"
trap 'rm -rf "$work"' EXIT
asset="vpn-chain-relay-linux-$arch.tar.gz"
curl -fsSL -o "$work/$asset" "$BASE_URL/$asset"
curl -fsSL -o "$work/$asset.sha256" "$BASE_URL/$asset.sha256"
(cd "$work" && sha256sum --quiet -c "$asset.sha256") \
    || die "$asset does not match its checksum; nothing was run"

mkdir "$work/bin"
tar -xzf "$work/$asset" -C "$work/bin" --no-same-owner
# Nothing may read the pipe the rest of this script is arriving on.
"$work/bin/relayd" install "$@" </dev/null
