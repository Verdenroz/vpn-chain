#!/usr/bin/env bash
# Download a pinned sing-box release into a cache dir and verify its sha256
# against the pin. Prints the path of the sing-box binary.
# Usage: fetch-singbox.sh 1.13.14
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=server/lib/pins.sh
. "$HERE/../lib/pins.sh"

VERSION="${1:?usage: fetch-singbox.sh VERSION}"
case "$(uname -m)" in
    x86_64) ARCH=amd64 ;;
    aarch64) ARCH=arm64 ;;
    *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
PIN="SINGBOX_SHA256_${VERSION//./_}_$ARCH"
[ -n "${!PIN:-}" ] || { echo "no pinned sha256 for sing-box $VERSION" >&2; exit 1; }
NAME="sing-box-$VERSION-linux-$ARCH"
CACHE="${XRAY_CACHE:-$HOME/.cache/vpn-chain-tests}/sing-box-$VERSION"

if [ ! -x "$CACHE/sing-box" ]; then
    mkdir -p "$CACHE"
    curl -fsSL -o "$CACHE/sb.tar.gz" "https://github.com/SagerNet/sing-box/releases/download/v$VERSION/$NAME.tar.gz"
    [ "$(sha256sum "$CACHE/sb.tar.gz" | cut -d' ' -f1)" = "${!PIN}" ] \
        || { echo "sing-box $VERSION differs from the pinned sha256" >&2; rm -f "$CACHE/sb.tar.gz"; exit 1; }
    tar -xzf "$CACHE/sb.tar.gz" --strip-components=1 -C "$CACHE" "$NAME/sing-box"
fi
printf '%s\n' "$CACHE/sing-box"
