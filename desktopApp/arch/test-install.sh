#!/usr/bin/env bash
# Installs and removes the package in a throwaway Arch container: test-install.sh PKG
# The container is neither privileged nor given systemd as PID 1.
set -euo pipefail

pkg="$(realpath "${1:?usage: test-install.sh PKG}")"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

docker run --rm --name "vpn-chain-arch-test-$$" -v "$pkg:/pkg/vpn-chain.pkg.tar.zst:ro" "$(cat "$here/image")" bash -euxc '
fails() { ! "$@" || { echo "unexpected success: $*" >&2; exit 1; }; }
pacman -Syu --noconfirm >/dev/null
pacman -U --noconfirm /pkg/vpn-chain.pkg.tar.zst >/dev/null

opt=/opt/vpn-chain
getcap "$opt/libexec/vpn-chain-killswitch" | grep -q "cap_net_admin=eip"
[ -z "$(getcap "$opt/libexec/sing-box")" ]
for f in "$opt/bin/vpn-chain" "$opt/libexec/sing-box" "$opt/libexec/vpn-chain-install-update" \
    /usr/lib/systemd/system/vpn-chain-relay.service /usr/lib/tmpfiles.d/vpn-chain.conf \
    /usr/share/polkit-1/rules.d/10-vpn-chain-resolve1.rules /usr/share/polkit-1/rules.d/10-vpn-chain-systemd.rules \
    /usr/share/polkit-1/actions/com.verdenroz.vpnchain.update.policy /usr/share/applications/vpn-chain.desktop \
    /usr/share/icons/hicolor/512x512/apps/vpn-chain.png /usr/share/icons/hicolor/scalable/apps/vpn-chain.svg; do
    [ -e "$f" ]
done
[ "$(cat "$opt/libexec/artifact-key")" = linux-x64-arch ]
[ "$(readlink -f /usr/bin/vpn-chain-killswitch)" = "$opt/libexec/vpn-chain-killswitch" ]
getent passwd sing-box
getent group vpn-chain
[ "$(stat -c "%a %U" /run/vpn-chain)" = "2770 sing-box" ]

systemd-analyze verify /usr/lib/systemd/system/vpn-chain-relay.service
"$opt/libexec/sing-box" version | head -1
"$opt/bin/vpn-chain" --version

update="$opt/libexec/vpn-chain-install-update"
fails "$update" "$(printf "0%.0s" $(seq 64))" </pkg/vpn-chain.pkg.tar.zst
fails "$update" "not-a-hash" </pkg/vpn-chain.pkg.tar.zst
echo not a package >/tmp/other
fails "$update" "$(sha256sum /tmp/other | cut -d" " -f1)" </tmp/other
"$update" "$(sha256sum /pkg/vpn-chain.pkg.tar.zst | cut -d" " -f1)" </pkg/vpn-chain.pkg.tar.zst

etc_unit=/etc/systemd/system/vpn-chain-relay.service
etc_rules=/etc/polkit-1/rules.d
mkdir -p "$etc_rules"
sed "s|^ExecStart=[^ ]*|ExecStart=/usr/local/bin/sing-box|" /usr/lib/systemd/system/vpn-chain-relay.service >"$etc_unit"
cp /usr/share/polkit-1/rules.d/10-vpn-chain-resolve1.rules "$etc_rules/"
cp /usr/share/polkit-1/rules.d/10-vpn-chain-systemd.rules "$etc_rules/"
echo "// local edit" >>"$etc_rules/10-vpn-chain-systemd.rules"
echo "keep me" >/etc/polkit-1/rules.d/50-unrelated.rules
pacman -U --noconfirm /pkg/vpn-chain.pkg.tar.zst >/tmp/reinstall.log 2>&1
[ ! -e "$etc_unit" ]
[ ! -e "$etc_rules/10-vpn-chain-resolve1.rules" ]
[ -e "$etc_rules/10-vpn-chain-systemd.rules" ]
[ "$(grep -c "^vpn-chain: " /tmp/reinstall.log)" = 1 ]
grep -q "vpn-chain: $etc_rules/10-vpn-chain-systemd.rules" /tmp/reinstall.log
[ "$(cat /etc/polkit-1/rules.d/50-unrelated.rules)" = "keep me" ]

pacman -R --noconfirm vpn-chain >/dev/null
[ ! -e "$opt/bin/vpn-chain" ]
[ ! -e /usr/lib/systemd/system/vpn-chain-relay.service ]
[ ! -e /usr/share/polkit-1/rules.d/10-vpn-chain-systemd.rules ]
fails getent passwd sing-box
fails getent group vpn-chain
[ -e "$etc_rules/10-vpn-chain-systemd.rules" ]
[ -e /etc/polkit-1/rules.d/50-unrelated.rules ]
rm "$etc_rules/10-vpn-chain-systemd.rules" /etc/polkit-1/rules.d/50-unrelated.rules
leftover="$(find /opt /usr/bin /run/vpn-chain /var/lib/vpn-chain /etc/tmpfiles.d /usr/share/polkit-1 /usr/share/applications \
    /usr/share/icons /usr/lib/systemd/system /usr/lib/tmpfiles.d \
    \( -iname "*vpn-chain*" -o -path "/opt/*" \) 2>/dev/null || true)"
[ -z "$leftover" ] || { echo "left behind: $leftover" >&2; exit 1; }
echo "install, update and remove ok"
'
