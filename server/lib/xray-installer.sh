#!/usr/bin/env bash
# Fetch the pinned Xray-install script. Sourced by deploy-server.sh; needs pins.sh and die().

# fetch_xray_installer DEST [URL]
fetch_xray_installer() {
    local dest="$1"
    local url="${2:-https://raw.githubusercontent.com/XTLS/Xray-install/$XRAY_INSTALL_COMMIT/install-release.sh}"
    curl -fsSL -o "$dest" "$url"
    [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$XRAY_INSTALL_SHA256" ] \
        || die "Xray installer differs from the pinned sha256; refusing to run it"
}
