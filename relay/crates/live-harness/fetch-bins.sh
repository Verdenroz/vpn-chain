#!/usr/bin/env bash
# Download the pinned linux-amd64 Xray and sing-box releases the live tests run,
# verify each against its pinned sha256, and print the directory holding them.
set -euo pipefail

XRAY_VERSION=v26.3.27
XRAY_SHA256=23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae
# The Android libbox AAR (gradle/libs.versions.toml) and the desktop sing-box.
SINGBOX_1_13_14_SHA256=f48703461a15476951ac4967cdad339d986f4b8096b4eb3ff0829a500502d697
SINGBOX_1_14_3_SHA256=e2bdf179c15a3652955dc44867e33ca0965c9c9b91b34267dcc5a5d639a5feee

ROOT="${XRAY_API_BINS:-${XDG_CACHE_HOME:-$HOME/.cache}/vpn-chain-tests/xray-api}"

fetch() { # url sha256 out
    local tmp
    tmp="$(mktemp "$3.XXXXXX")"
    curl -fsSL -o "$tmp" "$1"
    if [ "$(sha256sum "$tmp" | cut -d' ' -f1)" != "$2" ]; then
        rm -f "$tmp"; echo "checksum mismatch for $1" >&2; exit 1
    fi
    mv "$tmp" "$3"
}

xray() {
    local dir="$ROOT/xray-$XRAY_VERSION" base="https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION"
    [ -x "$dir/xray" ] && [ -f "$dir/geoip.dat" ] && return
    mkdir -p "$dir"
    fetch "$base/Xray-linux-64.zip" "$XRAY_SHA256" "$dir/xray.zip"
    curl -fsSL "$base/Xray-linux-64.zip.dgst" | grep -qx "SHA2-256= *$XRAY_SHA256" \
        || { echo "xray .dgst disagrees with the pin" >&2; exit 1; }
    # relayd renders geoip:private, which xray reads from geoip.dat beside the binary.
    unzip -oq "$dir/xray.zip" xray geoip.dat -d "$dir"
}

singbox() { # version sha256
    local dir="$ROOT/sing-box-$1" name="sing-box-$1-linux-amd64"
    [ -x "$dir/sing-box" ] && return
    mkdir -p "$dir"
    fetch "https://github.com/SagerNet/sing-box/releases/download/v$1/$name.tar.gz" "$2" "$dir/sing-box.tar.gz"
    tar -xzf "$dir/sing-box.tar.gz" -C "$dir" --strip-components=1 "$name/sing-box"
}

xray
singbox 1.13.14 "$SINGBOX_1_13_14_SHA256"
singbox 1.14.3 "$SINGBOX_1_14_3_SHA256"
printf '%s\n' "$ROOT"
