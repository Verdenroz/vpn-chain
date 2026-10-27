#!/usr/bin/env bash
# Download an Xray release (default: the pinned one) into a cache dir and verify
# its sha256 against the release's .dgst, and against the pin when it is the
# pinned release. Prints the path of the xray binary.
# Usage: fetch-xray.sh [vX.Y.Z]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=server/lib/pins.sh
. "$HERE/../lib/pins.sh"

VERSION="${1:-$XRAY_VERSION}"
case "$(uname -m)" in
    x86_64) ARCH=amd64 ASSET=Xray-linux-64 ;;
    aarch64) ARCH=arm64 ASSET=Xray-linux-arm64-v8a ;;
    *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
PIN="XRAY_SHA256_$ARCH"
CACHE="${XRAY_CACHE:-$HOME/.cache/vpn-chain-tests}/xray-$VERSION"
BASE="https://github.com/XTLS/Xray-core/releases/download/$VERSION"

if [ ! -x "$CACHE/xray" ]; then
    mkdir -p "$CACHE"
    curl -fsSL -o "$CACHE/xray.zip" "$BASE/$ASSET.zip"
    curl -fsSL -o "$CACHE/xray.zip.dgst" "$BASE/$ASSET.zip.dgst"
    want="$(sed -n 's/^SHA2-256= *//p' "$CACHE/xray.zip.dgst")"
    got="$(sha256sum "$CACHE/xray.zip" | cut -d' ' -f1)"
    [ -n "$want" ] && [ "$want" = "$got" ] || { echo "xray checksum mismatch" >&2; rm -f "$CACHE/xray.zip"; exit 1; }
    [ "$VERSION" != "$XRAY_VERSION" ] || [ "${!PIN}" = "$got" ] || { echo "xray differs from the pinned sha256" >&2; rm -f "$CACHE/xray.zip"; exit 1; }
    unzip -oq "$CACHE/xray.zip" xray geoip.dat geosite.dat -d "$CACHE"
fi
printf '%s\n' "$CACHE/xray"
