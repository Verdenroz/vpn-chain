#!/usr/bin/env bash
# Builds vpn-chain-<version>-1-x86_64.pkg.tar.zst: build-arch.sh APP_IMAGE OUT_DIR
# APP_IMAGE is the Compose app image, desktopApp/build/compose/binaries/main*/app/vpn-chain.
# Needs makepkg (base-devel), gcc with the libcap headers and curl, as a normal user
# on Arch; build-in-container.sh runs it in a container anywhere else.
set -euo pipefail

APP_IMAGE="$(realpath "${1:?usage: build-arch.sh APP_IMAGE OUT_DIR}")"
out="$(realpath -m "${2:?usage: build-arch.sh APP_IMAGE OUT_DIR}")"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

version="$(sed -n 's/^vpnchain.version=//p' "$REPO/gradle.properties")"
# The desktop ships the sing-box the Android core is built from.
singbox_version="$(sed -n 's/^libbox = "\(.*\)"/\1/p' "$REPO/gradle/libs.versions.toml")"
[ -x "$APP_IMAGE/bin/vpn-chain" ] || { echo "$APP_IMAGE has no bin/vpn-chain" >&2; exit 1; }

SINGBOX="$("$REPO/server/tests/fetch-singbox.sh" "$singbox_version")"
"$REPO/cli/killswitch/build.sh" >/dev/null

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# pkgver cannot hold a hyphen.
sed "s/@VERSION@/${version//-/_}/" "$HERE/PKGBUILD" >"$work/PKGBUILD"
cp "$HERE/vpn-chain.install" "$work/"

mkdir -p "$out"
(cd "$work" && APP_IMAGE="$APP_IMAGE" SINGBOX="$SINGBOX" REPO="$REPO" HERE="$HERE" PKGDEST="$out" makepkg --force --nodeps)
