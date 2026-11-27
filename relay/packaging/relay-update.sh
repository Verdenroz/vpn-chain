#!/usr/bin/env bash
# Runs the latest release's setup with --update, unless that release is the one last applied here.
set -euo pipefail

repo=https://github.com/Verdenroz/vpn-chain
stamp=/var/lib/vpn-chain/relay/release

tag="$(curl -fsSI "$repo/releases/latest" | tr -d '\r' \
    | sed -n 's|^[Ll]ocation: .*/releases/tag/||p')"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "relay-update: no release tag in the latest redirect" >&2; exit 1; }
[ "$(cat "$stamp" 2>/dev/null || true)" = "$tag" ] && exit 0

base="$repo/releases/download/$tag"
curl -fsSL "$base/setup.sh" | VPN_CHAIN_BASE_URL="$base" bash -s -- --update
printf '%s\n' "$tag" > "$stamp"
