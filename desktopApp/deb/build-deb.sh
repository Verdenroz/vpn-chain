#!/usr/bin/env bash
# Builds vpn-chain_<version>_amd64.deb: build-deb.sh APP_IMAGE OUT_DIR
# APP_IMAGE is the Compose app image, desktopApp/build/compose/binaries/main*/app/vpn-chain.
# Needs dpkg-deb, gcc with the libcap headers, curl and Gradle's output; run it on Debian or Ubuntu.
set -euo pipefail

image="$(realpath "${1:?usage: build-deb.sh APP_IMAGE OUT_DIR}")"
out="$(realpath -m "${2:?usage: build-deb.sh APP_IMAGE OUT_DIR}")"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

version="$(sed -n 's/^vpnchain.version=//p' "$repo/gradle.properties")"
# The desktop ships the sing-box the Android core is built from.
singbox_version="$(sed -n 's/^libbox = "\(.*\)"/\1/p' "$repo/gradle/libs.versions.toml")"
[ -x "$image/bin/vpn-chain" ] || { echo "$image has no bin/vpn-chain" >&2; exit 1; }

singbox="$("$repo/server/tests/fetch-singbox.sh" "$singbox_version")"
"$repo/cli/killswitch/build.sh" >/dev/null

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
prefix="$root/opt/vpn-chain"

install -d "$prefix" "$prefix/libexec"
cp -a "$image/." "$prefix/"
install -m 755 "$singbox" "$prefix/libexec/sing-box"
install -m 755 "$repo/cli/killswitch/vpn-chain-killswitch" "$prefix/libexec/"
install -m 755 "$here/vpn-chain-install-update" "$prefix/libexec/"
chmod -R u=rwX,go=rX "$prefix"
chmod 644 "$prefix/lib/libapplauncher.so"
find "$prefix" -type d -empty -delete

install -d "$root/usr/bin"
ln -s /opt/vpn-chain/libexec/vpn-chain-killswitch "$root/usr/bin/vpn-chain-killswitch"

install -d "$root/usr/lib/systemd/system" "$root/usr/lib/tmpfiles.d"
sed 's|^ExecStart=/usr/bin/sing-box|ExecStart=/opt/vpn-chain/libexec/sing-box|' \
    "$repo/cli/systemd/vpn-chain-relay.service" >"$root/usr/lib/systemd/system/vpn-chain-relay.service"
install -m 644 "$repo/cli/systemd/vpn-chain-tmpfiles.conf" "$root/usr/lib/tmpfiles.d/vpn-chain.conf"

install -d "$root/usr/share/polkit-1/rules.d" "$root/usr/share/polkit-1/actions"
install -m 644 "$repo"/cli/polkit/*.rules "$root/usr/share/polkit-1/rules.d/"
install -m 644 "$here/com.verdenroz.vpnchain.update.policy" "$root/usr/share/polkit-1/actions/"

install -d "$root/usr/share/applications" "$root/usr/share/icons/hicolor/512x512/apps" "$root/usr/share/icons/hicolor/scalable/apps"
install -m 644 "$here/vpn-chain.desktop" "$root/usr/share/applications/"
install -m 644 "$repo/desktopApp/icons/vpn-chain.png" "$root/usr/share/icons/hicolor/512x512/apps/"
install -m 644 "$repo/assets/branding/icon.svg" "$root/usr/share/icons/hicolor/scalable/apps/vpn-chain.svg"

doc="$root/usr/share/doc/vpn-chain"
install -d "$doc" "$root/usr/share/lintian/overrides"
install -m 644 "$here/copyright" "$doc/"
printf 'vpn-chain (%s) stable; urgency=medium\n\n  * Release %s.\n\n -- Harvey Tseng <verde@nroz.dev>  %s\n' \
    "$version" "$version" "$(date -R -u -d "@${SOURCE_DATE_EPOCH:-$(date +%s)}")" | gzip -9n >"$doc/changelog.gz"
install -m 644 "$here/lintian-overrides" "$root/usr/share/lintian/overrides/vpn-chain"

install -d "$root/DEBIAN"
sed "s/@VERSION@/$version/; s/@SIZE@/$(du -sk "$root" | cut -f1)/" "$here/control" >"$root/DEBIAN/control"
install -m 755 "$here/postinst" "$here/prerm" "$here/postrm" "$root/DEBIAN/"
(cd "$root" && find . -type f ! -path './DEBIAN/*' -printf '%P\0' | sort -z | xargs -0 md5sum) >"$root/DEBIAN/md5sums"

mkdir -p "$out"
dpkg-deb --root-owner-group -Zxz --build "$root" "$out/vpn-chain_${version}_amd64.deb"
