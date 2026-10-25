#!/usr/bin/env bash
# Download the pinned Xray release into a cache dir and verify its sha256
# against the release's .dgst. Prints the path of the xray binary.
set -euo pipefail

XRAY_VERSION="${XRAY_VERSION:-v26.10.21}"
CACHE="${XRAY_CACHE:-$HOME/.cache/vpn-chain-tests}/xray-$XRAY_VERSION"
BASE="https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION"

if [ ! -x "$CACHE/xray" ]; then
    mkdir -p "$CACHE"
    curl -fsSL -o "$CACHE/xray.zip" "$BASE/Xray-linux-64.zip"
    curl -fsSL -o "$CACHE/xray.zip.dgst" "$BASE/Xray-linux-64.zip.dgst"
    want="$(sed -n 's/^SHA2-256= *//p' "$CACHE/xray.zip.dgst")"
    got="$(sha256sum "$CACHE/xray.zip" | cut -d' ' -f1)"
    [ -n "$want" ] && [ "$want" = "$got" ] || { echo "xray checksum mismatch" >&2; rm -f "$CACHE/xray.zip"; exit 1; }
    unzip -oq "$CACHE/xray.zip" xray geoip.dat geosite.dat -d "$CACHE"
fi
printf '%s\n' "$CACHE/xray"
