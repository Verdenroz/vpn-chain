#!/usr/bin/env bash
# Boots a fresh Debian or Ubuntu VPS in a container with systemd as PID 1, installs on it the
# way a user does (setup.sh with a release served on loopback), and drives the relay it made
# with the sing-box clients the apps ship:
#   a) a download that fails its checksum installs nothing; a fresh install prints one pairing link, leaves no copy of it,
#      and prints a new one when run again until a device has enrolled
#   b) the link connects; enrolling with a P-256 key works once, a used token gets 401, an
#      expired one 410, a replaced one 401, and the old identity is refused afterwards
#   c) the nft rules stop a name that rebinds to loopback, keep the agent and the internet
#      reachable, leave other tables alone and come back with the unit
#   d) a relay made by server/deploy-server.sh is adopted with its keypair and clients intact
# Usage: run.sh [debian|ubuntu]. The containers are never privileged: SYS_ADMIN for systemd's
# cgroup, NET_ADMIN for nft.
set -eEuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY="$(cd "$HERE/../../../.." && pwd)"
ROOT="$(cd "$RELAY/.." && pwd)"
DISTRO="${1:-debian}"
case "$DISTRO" in
    debian) SUBNET=11.253.78 PRIV_SUBNET=198.19.78 ;;
    ubuntu) SUBNET=11.253.79 PRIV_SUBNET=198.19.79 ;;
    *) echo "usage: run.sh [debian|ubuntu]" >&2; exit 64 ;;
esac
RELAY_C="relay-$DISTRO-$$"
SINGBOX_VERSIONS=(1.13.14 1.14.3)
STATE_DIR=/var/lib/vpn-chain/relay
# shellcheck source=relay/crates/relayd/tests/install/lib.sh
. "$HERE/lib.sh"

step "build static binaries, fetch the clients, boot a fresh $DISTRO VPS"
TARGET="$(uname -m)-unknown-linux-musl"
# shellcheck disable=SC2086
(cd "$RELAY" && ${CARGO:-cargo} build -q --locked --target "$TARGET" -p relayd -p relay-jobs)
BUILD="${CARGO_TARGET_DIR:-$RELAY/target}/$TARGET/debug"
SING_BOX="$("$RELAY/crates/live-harness/fetch-bins.sh")"
docker build -q -t "$TEST_IMG" -f "$ROOT/server/tests/Dockerfile" "$ROOT/server/tests" >/dev/null
docker build -q -t "$IMG" --build-arg "BASE=$BASE" "$HERE" >/dev/null
lab_up
relay_up "$RELAY_C" "$(addr 10)"
case "$DISTRO" in
    debian) g bash -c 'install -d -m700 /root/.ssh && echo "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA test" >/root/.ssh/authorized_keys' ;;
esac

step "a) a download that does not match its checksum installs nothing"
release_up
g bash -c 'cd /release && printf "%064d  %s\n" 0 vpn-chain-relay-linux-*.tar.gz >bad && mv bad vpn-chain-relay-linux-*.tar.gz.sha256'
no setup_install --host "$(addr 10)" --sni dest.test >/dev/null 2>"$W/err-sum"
grep -q 'does not match its checksum' "$W/err-sum"
no g test -e $STATE_DIR/relay.json
no g test -e /usr/local/bin/relayd
release_pack
pass "setup.sh aborts on a wrong checksum before installing anything"

step "a) a fresh install through setup.sh"
setup_install --host "$(addr 10)" --sni dest.test >"$W/out" 2>"$W/err"
eq "$(grep -c 'vpnchain://pair' "$W/out")" 1
grep -q '█' "$W/out"
eq "$(grep -c 'vpnchain://' "$W/err" || true)" 0
pass "stdout holds the link once, as text and a QR code, and stderr never shows it"
eq "$(link_field "$W/out" n)" admin
eq "$(link_field "$W/out" h)" "$(addr 10)"
eq "$(link_field "$W/out" v)" 1
eq "$(link_field "$W/out" f)" 1
first_ttl=$(( $(link_field "$W/out" e) - $(g date +%s) ))
[ "$first_ttl" -gt 86300 ] && [ "$first_ttl" -le 86400 ]
pass "the link names the admin device and the host, is marked as the first admin's, and expires in about 24 hours"
for unit in xray relayd vpn-chain-egress relay-privileged.path; do
    eq "$(g systemctl is-active "$unit")" active
done
eq "$(g ps -o user= -C xray)" xray
eq "$(g ps -o args= -C xray)" "/opt/vpn-chain/xray/current/xray run -config /usr/local/etc/xray/config.json"
eq "$(g readlink /opt/vpn-chain/xray/current)" 26.3.27
eq "$(g stat -c '%U:%G %a' $STATE_DIR/relay.json $STATE_DIR/relay.secrets.json | paste -sd' ')" "relayd:relayd 600 relayd:relayd 600"
eq "$(g stat -c '%U:%G %a' /usr/local/etc/xray/config.json)" relayd:xray\ 640
pass "xray and relayd run as their own users, and the keys are in private files"
TOKEN="$(link_field "$W/out" t)"
LINK="$(grep -o 'vpnchain://[^ ]*' "$W/out")"
no g grep -rlF -e "$TOKEN" -e "$LINK" /var /etc /root /usr/local /opt/vpn-chain
no g bash -c "journalctl --no-pager -a | grep -F -e '$TOKEN'"
pass "neither the link nor its token is in any file or log on the VPS"
case "$DISTRO" in
    debian)
        eq "$(g sshd -T | grep -ci '^passwordauthentication no')" 1
        grep -q ':22 ' <<<"$(g ss -ltn)"
        grep -q 'key-only' "$W/err"
        pass "password login is off, the key stays and port 22 still listens" ;;
    ubuntu)
        g mkdir -p /run/sshd
        eq "$(g sshd -T | grep -ci '^passwordauthentication yes')" 1
        grep -q 'has an authorized SSH key' "$W/err"
        pass "without an authorized key password login stays on and install says so" ;;
esac

step "a) running install again before a device enrolls prints a new code and changes nothing else"
snapshot() {
    g bash -c "cd / && sha256sum \$(ls $STATE_DIR/*.json | grep -v enroll.json) /usr/local/etc/xray/config.json /etc/systemd/system/*.service \
        /etc/ssh/sshd_config.d/* /usr/local/bin/relay* 2>/dev/null; nft list ruleset | sha256sum; \
        systemctl show xray relayd -p MainPID; ls /usr/local/etc/xray"
}
snapshot >"$W/before"
g /usr/local/bin/relayd install --host "$(addr 10)" --sni dest.test >"$W/out2" 2>"$W/err2"
snapshot >"$W/after"
diff "$W/before" "$W/after"
eq "$(grep -c 'vpnchain://pair' "$W/out2")" 1
eq "$(grep -c 'vpnchain://' "$W/err2" || true)" 0
grep -q 'already installed' "$W/err2"
grep -q 'for 24 hours' "$W/out2"
[ "$(link_field "$W/out2" t)" != "$TOKEN" ]
eq "$(link_field "$W/out2" f)" 1
second_ttl=$(( $(link_field "$W/out2" e) - $(g date +%s) ))
[ "$second_ttl" -gt 86300 ] && [ "$second_ttl" -le 86400 ]
NEW_TOKEN="$(link_field "$W/out2" t)"
NEW_LINK="$(grep -o 'vpnchain://[^ ]*' "$W/out2")"
no g grep -rlF -e "$NEW_TOKEN" -e "$NEW_LINK" /var /etc /root /usr/local /opt/vpn-chain
pass "state, config, units, rules and processes are identical, and the second link is new, lasts a day and is kept nowhere"
no g /usr/local/bin/relayd install --port 8443 2>"$W/err4"
grep -q 'already has one' "$W/err4"
pass "a flag that only shapes a new relay is refused on one that exists"

if [ "$DISTRO" = ubuntu ]; then
    step "a) a key added later turns key-only login on at the next run"
    g bash -c 'install -d -m700 /root/.ssh && echo "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA test" >/root/.ssh/authorized_keys'
    g /usr/local/bin/relayd install >"$W/out2" 2>"$W/err3"
    eq "$(g sshd -T | grep -ci '^passwordauthentication no')" 1
    pass "running install again after adding a key hardens SSH, and nothing else changes"
fi

step "a) other nft tables are left alone"
g nft add table inet userfw
g nft add chain inet userfw input '{ type filter hook input priority 0; policy accept; }'
g nft add rule inet userfw input tcp dport 22 accept
USER_RULES="$(g nft list table inet userfw)"
g /usr/local/bin/relayd egress --state $STATE_DIR/relay.json
eq "$(g nft list table inet userfw)" "$USER_RULES"
grep -q "meta skuid != $(g id -u xray) return" <<<"$(g nft list table inet vpn_chain_egress)"
pass "applying the egress rules leaves another table as it was"

g bash -c 'mkdir -p /srv/loop && echo loopback >/srv/loop/index.html'
docker exec -d "$RELAY_C" sh -c 'cd /srv/loop && exec python3 -m http.server 80 --bind 127.0.0.1'
docker exec -d "$RELAY_C" sh -c 'cd /srv/loop && exec python3 -m http.server 8090 --bind 0.0.0.0'
sleep 1

n=0
for version in "${SINGBOX_VERSIONS[@]}"; do
    tag="${version//./-}"
    n=$((n + 1))
    step "b) sing-box $version: connect with the link, enroll, then reuse and expiry"
    if [ "$n" -eq 1 ]; then
        cp "$W/out2" "$W/link-$tag"
    else
        g relayd pair >"$W/link-$tag"
    fi
    client_from "c-$tag" "$version" "$W/link-$tag"
    eq "$(through "c-$tag" "http://$(addr 50)/")" public
    eq "$(through "c-$tag" http://agent.vpn-chain.invalid/v1/relay -o /dev/null -w '%{http_code}')" 401
    pass "sing-box $version connects with the identity in the link, and the agent wants a signature"

    new_key "k-$tag"
    if [ "$n" -eq 1 ]; then
        eq "$(enroll "c-$tag" "$TOKEN" "k-$tag" | cut -d' ' -f1)" 401
        pass "the first run's code was replaced by the second, so it gets 401"
    fi
    reply="$(enroll "c-$tag" "$(link_field "$W/link-$tag" t)" "k-$tag")"
    eq "${reply%% *}" 200
    body="${reply#* }"
    key_id="$(jq -r .keyId <<<"$body")"
    eq "${#key_id}" 16
    new_uuid="$(jq -r .identity.uuid <<<"$body")"
    new_sid="$(jq -r .identity.shortId <<<"$body")"
    [ "$new_uuid" != "$(link_field "$W/link-$tag" id)" ]
    pass "enrolling returns a new identity and a key id"

    client_from "e-$tag" "$version" "$W/link-$tag" "$new_uuid" "$new_sid"
    eq "$(through "e-$tag" "http://$(addr 50)/")" public
    eq "$(signed "e-$tag" "k-$tag" "$key_id" GET /v1/relay)" 200
    pass "the new identity connects and a signed GET /v1/relay succeeds"

    eq "$(enroll "e-$tag" "$(link_field "$W/link-$tag" t)" "k-$tag" | cut -d' ' -f1)" 401
    pass "the same QR a second time gets 401"

    if [ "$n" -eq 1 ]; then
        g /usr/local/bin/relayd install --host "$(addr 10)" --sni dest.test >"$W/out3" 2>"$W/err3"
        no grep -q 'vpnchain://' "$W/out3" "$W/err3"
        grep -q 'already set up; add devices from the app' "$W/err3"
        pass "running install again after a device enrolled prints no code and points to the app"
    fi

    client_from "o-$tag" "$version" "$W/link-$tag"
    eq "$(through "o-$tag" "http://$(addr 50)/")" ""
    pass "the old identity is refused after enrolling"

    g relayd pair >"$W/link-exp-$tag"
    g python3 -I -c "import json; p='$STATE_DIR/enroll.json'; d=json.load(open(p)); [t.update(expires=1) for t in d['pending']]; json.dump(d, open(p, 'w'))"
    client_from "x-$tag" "$version" "$W/link-exp-$tag"
    eq "$(enroll "x-$tag" "$(link_field "$W/link-exp-$tag" t)" "k-$tag" | cut -d' ' -f1)" 410
    pass "an expired token gets 410"

    step "c) sing-box $version: the nft rules"
    ctl="rb-$tag-control.test"
    eq "$(through "e-$tag" "http://rb-$tag-first.test:80/")" ""
    eq "$(through "e-$tag" "http://rb-$tag-stub-first.test:8090/")" ""
    pass "a name that rebinds to loopback, or to the resolver stub at another port, is refused with the rules loaded"
    g nft delete table inet vpn_chain_egress
    eq "$(through "e-$tag" "http://rb-$tag-stub-open.test:8090/")" loopback
    eq "$(through "e-$tag" "http://$ctl:80/")" loopback
    pass "with the table removed the same probes reach loopback and the stub, so the rules are what stop them"
    g /usr/local/bin/relayd egress --state $STATE_DIR/relay.json
    eq "$(through "e-$tag" "http://rb-$tag-again.test:80/")" ""
    eq "$(through "e-$tag" "http://public.test/")" public
    eq "$(through "e-$tag" "http://$(addr 50)/")" public
    eq "$(through "e-$tag" "http://$PRIV_SUBNET.50/")" ""
    eq "$(signed "e-$tag" "k-$tag" "$key_id" GET /v1/relay)" 200
    pass "after the rules are back: a public name and address work, a private one does not, the agent answers"
done

step "c) the rules and xray come back with the unit, as after a reboot"
g systemctl stop vpn-chain-egress
no g nft list table inet vpn_chain_egress 2>/dev/null
g systemctl start xray
for _ in $(seq 60); do grep -q ':443 ' <<<"$(g ss -ltn)" && break; sleep 0.5; done
g nft list table inet vpn_chain_egress >/dev/null
eq "$(through "e-$tag" "http://$(addr 50)/")" public
reply="$(signed_full "e-$tag" "k-$tag" "$key_id" POST /v1/devices '{"name":"tablet"}')"
eq "${reply%% *}" 201
tablet="${reply#* }"
client_up tablet "$version" "$(addr 10)" 443 dest.test "$(link_field "$W/link-$tag" pbk)" \
    "$(jq -r .identity.shortId <<<"$tablet")" "$(jq -r .identity.uuid <<<"$tablet")"
eq "$(through tablet "http://$(addr 50)/")" public
eq "$(g systemctl is-enabled vpn-chain-egress xray relayd | paste -sd' ')" "enabled enabled enabled"
pass "starting xray loads the rules first, and relayd still adds a device through xray's API"

# adopt_case LABEL IP PAIRED INSTALL_FLAG...: a VPS that deploy-server.sh set up with two
# devices, adopted with the given flags. PAIRED is the device the printed link is for.
adopt_case() {
    local label="$1" ip="$2" paired="$3" xray_dir="$SING_BOX/xray-v26.3.27"
    shift 3
    local c="adopt-$label-$DISTRO-$$" version="${SINGBOX_VERSIONS[1]}"
    relay_up "$c" "$ip"
    RELAY_C="$c"
    docker cp "$xray_dir/xray" "$c:/usr/local/bin/xray"
    g install -d /usr/local/share/xray /usr/local/etc/xray /var/log/xray
    docker cp "$xray_dir/geoip.dat" "$c:/usr/local/share/xray/geoip.dat"
    g bash -c 'cat >/etc/systemd/system/xray.service <<EOF
[Unit]
After=network.target
[Service]
User=nobody
ExecStart=/usr/local/bin/xray run -config /usr/local/etc/xray/config.json
Restart=on-failure
EOF
touch /var/log/xray/error.log /var/log/xray/access.log && chown -R nobody /var/log/xray'
    g env DEPLOY_SKIP_INSTALL=1 XRAY_BIN=/usr/local/bin/xray VPS_IP="$ip" SNI=dest.test DEST=dest.test:443 \
        SETTLE=1 DEVICE_NAME=device1 DEVICE_FILE_DIR=/root bash /repo/server/deploy-server.sh >/dev/null 2>&1
    g env SETTLE=1 bash /repo/server/xray-clients.sh add phone >"$W/phone-$label.env" 2>/dev/null
    local old_uuid old_sid pbk phone_uuid phone_sid
    old_uuid="$(g sed -n 's/^VLESS_UUID=//p' /root/vpn-chain-device1.env)"
    old_sid="$(g sed -n 's/^SHORT_ID=//p' /root/vpn-chain-device1.env)"
    pbk="$(g sed -n 's/^REALITY_PUBKEY=//p' /root/vpn-chain-device1.env)"
    phone_uuid="$(sed -n 's/^UUID=//p' "$W/phone-$label.env")"
    phone_sid="$(sed -n 's/^SHORT_ID=//p' "$W/phone-$label.env")"
    client_up "$label-before-1" "$version" "$ip" 443 dest.test "$pbk" "$old_sid" "$old_uuid"
    client_up "$label-before-2" "$version" "$ip" 443 dest.test "$pbk" "$phone_sid" "$phone_uuid"
    eq "$(through "$label-before-1" "http://$(addr 50)/")" public
    eq "$(through "$label-before-2" "http://$(addr 50)/")" public
    pass "$label: the deploy-server.sh relay serves two devices before the adoption"

    release_up
    setup_install --host "$ip" "$@" >"$W/out-$label" 2>"$W/err-$label"
    grep -q 'adopting' "$W/err-$label"
    eq "$(link_field "$W/out-$label" n)" "$paired"
    eq "$(link_field "$W/out-$label" pbk)" "$pbk"
    case "$paired" in
        device1) eq "$(link_field "$W/out-$label" id)" "$old_uuid"; eq "$(link_field "$W/out-$label" sid)" "$old_sid" ;;
        *) [ "$(link_field "$W/out-$label" id)" != "$old_uuid" ]; [ "$(link_field "$W/out-$label" id)" != "$phone_uuid" ] ;;
    esac
    eq "$(link_field "$W/out-$label" f)" 1
    grep -q 'keep working' "$W/err-$label"
    pass "$label: the link is for $paired, carries the existing public key, and the output says existing devices keep working"
    client_up "$label-after-1" "$version" "$ip" 443 dest.test "$pbk" "$old_sid" "$old_uuid"
    client_up "$label-after-2" "$version" "$ip" 443 dest.test "$pbk" "$phone_sid" "$phone_uuid"
    eq "$(through "$label-after-1" "http://$(addr 50)/")" public
    eq "$(through "$label-after-2" "http://$(addr 50)/")" public
    eq "$(g ps -o user= -C xray)" xray
    pass "$label: both existing clients still connect after the adoption, now to an xray run by the xray user"

    new_key "adopt-$label"
    local reply names
    reply="$(enroll "$label-after-1" "$(link_field "$W/out-$label" t)" "adopt-$label")"
    eq "${reply%% *}" 200
    client_from "$label-paired" "$version" "$W/out-$label" "$(jq -r .identity.uuid <<<"${reply#* }")" "$(jq -r .identity.shortId <<<"${reply#* }")"
    reply="$(signed_full "$label-paired" "adopt-$label" "$(jq -r .keyId <<<"${reply#* }")" GET /v1/devices)"
    eq "${reply%% *}" 200
    names="$(jq -r '.devices | map(.name) | sort | join(",")' <<<"${reply#* }")"
    case "$paired" in
        device1) eq "$names" device1,phone ;;
        *) eq "$names" "device1,phone,$paired" ;;
    esac
    eq "$(through "$label-after-2" "http://$(addr 50)/")" public
    pass "$label: $paired enrolls as an admin and lists $names; the phone is unaffected"
}

step "b) a second device is paired without signing out the first"
sleep 61 # the failed enrolls above fill the agent's five-a-minute limit
g relayd pair --new phone >"$W/link-phone"
eq "$(g stat -c '%U:%G %a' $STATE_DIR/relay.json $STATE_DIR/relay.secrets.json | paste -sd' ')" "relayd:relayd 600 relayd:relayd 600"
eq "$(g stat -c '%U:%G %a' /usr/local/etc/xray/config.json)" relayd:xray\ 640
eq "$(link_field "$W/link-phone" n)" phone
client_from phone-1 "$version" "$W/link-phone"
eq "$(through phone-1 "http://$(addr 50)/")" public
eq "$(through "e-$tag" "http://$(addr 50)/")" public
new_key phone
reply="$(enroll phone-1 "$(link_field "$W/link-phone" t)" phone)"
eq "${reply%% *}" 200
phone_key="$(jq -r .keyId <<<"${reply#* }")"
client_from phone-2 "$version" "$W/link-phone" "$(jq -r .identity.uuid <<<"${reply#* }")" "$(jq -r .identity.shortId <<<"${reply#* }")"
reply="$(signed_full phone-2 phone "$phone_key" GET /v1/devices)"
eq "$(jq -r '.devices | map(.name + ":" + .role) | join(",")' <<<"${reply#* }")" phone:member
reply="$(signed_full "e-$tag" "k-$tag" "$key_id" GET /v1/devices)"
eq "$(jq -r '.devices | map(.name + ":" + .role) | sort | join(",")' <<<"${reply#* }")" "admin:admin,phone:member,tablet:member"
pass "pair --new adds a member through the API, relayd keeps its files, enrollment makes it a member, and the admin device keeps working"
g relayd pair >/dev/null 2>"$W/err5"
grep -q 'signs out the app' "$W/err5"
pass "a plain pair says it re-pairs the admin device"
grep -q 'phone *member' <<<"$(g relayd device list)"
pass "device list on the VPS needs no flags"

step "d) a relay deployed by deploy-server.sh is adopted, pairing an existing device"
adopt_case existing "$(addr 100)" device1 --device device1
step "d) the same, pairing a new admin device"
adopt_case newdev "$(addr 101)" tablet2 --name tablet2

printf '\nrun.sh %s: %d checks passed\n' "$DISTRO" "$PASS"
