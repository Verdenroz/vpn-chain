#!/usr/bin/env bash
# Installs, removes and purges the package in a throwaway container:
# test-install.sh DEB IMAGE   (IMAGE is debian:trixie-slim, ubuntu:24.04, ...)
# The container is neither privileged nor given systemd as PID 1.
set -euo pipefail

deb="$(realpath "${1:?usage: test-install.sh DEB IMAGE}")"
image="${2:?usage: test-install.sh DEB IMAGE}"

docker run --rm --name "vpn-chain-deb-test-$$" -v "$deb:/pkg/vpn-chain.deb:ro" "$image" bash -euxc '
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq /pkg/vpn-chain.deb >/dev/null

opt=/opt/vpn-chain
getcap "$opt/libexec/vpn-chain-killswitch" | grep -q "cap_net_admin=eip"
[ -z "$(getcap "$opt/libexec/sing-box")" ]
for f in "$opt/bin/vpn-chain" "$opt/libexec/sing-box" "$opt/libexec/vpn-chain-install-update" \
    /usr/lib/systemd/system/vpn-chain-relay.service /usr/lib/tmpfiles.d/vpn-chain.conf \
    /usr/share/polkit-1/rules.d/10-vpn-chain-resolve1.rules /usr/share/polkit-1/rules.d/10-vpn-chain-systemd.rules \
    /usr/share/polkit-1/actions/com.verdenroz.vpnchain.update.policy /usr/share/applications/vpn-chain.desktop \
    /usr/share/icons/hicolor/512x512/apps/vpn-chain.png; do
    [ -e "$f" ]
done
[ "$(readlink -f /usr/bin/vpn-chain-killswitch)" = "$opt/libexec/vpn-chain-killswitch" ]
getent passwd sing-box
getent group vpn-chain
[ "$(stat -c "%a %U" /run/vpn-chain)" = "2770 sing-box" ]

systemd-analyze verify /usr/lib/systemd/system/vpn-chain-relay.service
"$opt/libexec/sing-box" version | head -1
"$opt/bin/vpn-chain" --version

update="$opt/libexec/vpn-chain-install-update"
! "$update" "$(printf "0%.0s" $(seq 64))" </pkg/vpn-chain.deb
! "$update" "not-a-hash" </pkg/vpn-chain.deb
"$update" "$(sha256sum /pkg/vpn-chain.deb | cut -d" " -f1)" </pkg/vpn-chain.deb

etc_unit=/etc/systemd/system/vpn-chain-relay.service
etc_rules=/etc/polkit-1/rules.d
mkdir -p "$etc_rules"
sed "s|^ExecStart=[^ ]*|ExecStart=/usr/local/bin/sing-box|" /usr/lib/systemd/system/vpn-chain-relay.service >"$etc_unit"
cp /usr/share/polkit-1/rules.d/10-vpn-chain-resolve1.rules "$etc_rules/"
cp /usr/share/polkit-1/rules.d/10-vpn-chain-systemd.rules "$etc_rules/"
echo "// local edit" >>"$etc_rules/10-vpn-chain-systemd.rules"
echo "keep me" >/etc/polkit-1/rules.d/50-unrelated.rules
apt-get install --reinstall -y /pkg/vpn-chain.deb >/tmp/reinstall.err 2>&1
[ ! -e "$etc_unit" ]
[ ! -e "$etc_rules/10-vpn-chain-resolve1.rules" ]
[ -e "$etc_rules/10-vpn-chain-systemd.rules" ]
[ "$(grep -c "^vpn-chain: " /tmp/reinstall.err)" = 1 ]
grep -q "vpn-chain: $etc_rules/10-vpn-chain-systemd.rules" /tmp/reinstall.err
[ "$(cat /etc/polkit-1/rules.d/50-unrelated.rules)" = "keep me" ]

apt-get remove -y -qq vpn-chain >/dev/null
[ ! -e "$opt/bin/vpn-chain" ]
[ ! -e /usr/lib/systemd/system/vpn-chain-relay.service ]
[ ! -e /usr/share/polkit-1/rules.d/10-vpn-chain-systemd.rules ]
getent passwd sing-box

apt-get purge -y -qq vpn-chain >/dev/null
! getent passwd sing-box
! getent group vpn-chain
[ -e "$etc_rules/10-vpn-chain-systemd.rules" ]
[ -e /etc/polkit-1/rules.d/50-unrelated.rules ]
rm "$etc_rules/10-vpn-chain-systemd.rules" /etc/polkit-1/rules.d/50-unrelated.rules
leftover="$(find /opt /usr/bin /run/vpn-chain /var/lib/vpn-chain /etc/tmpfiles.d /usr/share/polkit-1 /usr/share/applications \
    /usr/share/icons /usr/share/doc/vpn-chain /usr/lib/systemd/system /usr/lib/tmpfiles.d \
    \( -iname "*vpn-chain*" -o -path "/opt/*" \) 2>/dev/null || true)"
[ -z "$leftover" ] || { echo "left behind: $leftover" >&2; exit 1; }
echo "install, remove and purge ok"
'
