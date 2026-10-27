#!/usr/bin/env bash
# shellcheck disable=SC2016 # bash -c bodies expand in the child
# deploy-server.sh with the installer, ufw and systemd skipped.
# shellcheck source=server/tests/lib.sh
. "$(dirname "$0")/lib.sh"

setup() {
    W="$(mktemp -d)"
    mkdir "$W/xray"
    export XRAY_CONFIG="$W/xray/config.json" SIDECAR="$W/xray/devices.json" CFG="$W/xray/config.json"
    export DEPLOY_SKIP_INSTALL=1 XRAY_BIN="$XRAY" VPS_IP=203.0.113.10 SNI=www.example.com
    export DEVICE_FILE_DIR="$W" RELOAD_CMD=true ACTIVE_CMD=true LOCK="$W/lock" SETTLE=0 STATS_CMD=
    unset FRESH DEVICE_NAME EXIT_WG_PRIVKEY
}
deploy() {
    ALL="$(bash "$SERVER/deploy-server.sh" 2>&1)"
    RC=$?
}
inb() { jq -r --arg tag vless-reality-443 ".inbounds[] | select(.tag == \$tag) | $1" "$CFG"; }

# ---- fresh deploy --------------------------------------------------------
setup
deploy
F="$W/vpn-chain-device1.env"
UUID="$(inb '.settings.clients[0].id')"
SID="$(inb '.streamSettings.realitySettings.shortIds[0]')"
check "fresh deploy exits 0" test "$RC" -eq 0
check "generated config passes xray -test" valid
check "first client is named device1" test "$(clients)" = device1
check "one short ID" test "$(sids)" = "$SID"
check "sidecar maps device1 to its short ID" test "$(jq -r .device1.shortId "$SIDECAR")" = "$SID"
check "sidecar is 0600" test "$(stat -c %a "$SIDECAR")" = 600
check "device file is 0600" test "$(stat -c %a "$F")" = 600
check "device file carries the identity" bash -c 'grep -qx "VLESS_UUID=$2" "$1" && grep -qx "SHORT_ID=$3" "$1" && grep -qx DEVICE_NAME=device1 "$1" && grep -qx VPS_IP=203.0.113.10 "$1" && grep -q "^REALITY_PUBKEY=." "$1"' _ "$F" "$UUID" "$SID"
check "deploy output never shows the uuid" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$UUID"
check "deploy output never shows the short ID" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$SID"
check "deploy output has no vless link" bash -c '! grep -q "vless://" <<<"$1"' _ "$ALL"
check "deploy output names the device file" bash -c 'grep -q "$2" <<<"$1"' _ "$ALL" "$F"
check "access log stays off" test "$(jq -r .log.access "$CFG")" = none
check "stats API listens on loopback only" test "$(jq -r '.inbounds[] | select(.tag == "api") | .listen' "$CFG")" = 127.0.0.1
check "per-user counters enabled" test "$(jq -r '.policy.levels["0"] | .statsUserUplink and .statsUserDownlink' "$CFG")" = true
check "api rule routes before the catch-all" test "$(jq -r '.routing.rules[0].inboundTag[0]' "$CFG")" = api
check "devices.sh list sees no drift" bash -c '! bash "$1/xray-clients.sh" list 2>&1 | grep -q WARN' _ "$SERVER"

# ---- re-run keeps everything ---------------------------------------------
PRIV="$(inb '.streamSettings.realitySettings.privateKey')"
cp "$SIDECAR" "$W/sc-before"
rm "$F"
deploy
check "re-run exits 0" test "$RC" -eq 0
check "re-run keeps the uuid" test "$(inb '.settings.clients[0].id')" = "$UUID"
check "re-run keeps the short ID" test "$(sids)" = "$SID"
check "re-run keeps the keypair" test "$(inb '.streamSettings.realitySettings.privateKey')" = "$PRIV"
check "re-run keeps the sidecar" cmp "$SIDECAR" "$W/sc-before"
check "re-run writes no device file" test ! -e "$F"
check "re-run keeps the config valid" valid
check "re-run backs up the old config" bash -c 'ls "$1"/config.json.bak.* >/dev/null' _ "$W/xray"

bash "$SERVER/xray-clients.sh" add watch >/dev/null 2>&1
deploy
check "re-run keeps devices added later" test "$(clients)" = "device1,watch"
check "re-run keeps their short IDs" test "$(jq -r .watch.shortId "$SIDECAR")" != null -a "$(jq '.inbounds[0].streamSettings.realitySettings.shortIds | length' "$CFG")" -eq 2

# ---- FRESH=1 -------------------------------------------------------------
FRESH=1 deploy
check "FRESH=1 exits 0" test "$RC" -eq 0
check "FRESH=1 drops added devices" test "$(clients)" = device1
check "FRESH=1 makes a new keypair" test "$(inb '.streamSettings.realitySettings.privateKey')" != "$PRIV"
check "FRESH=1 makes a new uuid" test "$(inb '.settings.clients[0].id')" != "$UUID"
check "FRESH=1 resets the sidecar" test "$(jq -r 'keys | join(",")' "$SIDECAR")" = device1
check "FRESH=1 keeps a sidecar backup" bash -c 'ls "$1"/devices.json.bak.* >/dev/null' _ "$W/xray"

# ---- DEVICE_NAME, proton chain -------------------------------------------
setup
DEVICE_NAME=laptop EXIT_WG_PRIVKEY="$(openssl rand -base64 32)" EXIT_WG_PEER_PUBKEY="$(openssl rand -base64 32)" \
    EXIT_WG_ENDPOINT=198.51.100.9:51820 deploy
check "named device with a Proton chain deploys" test "$RC" -eq 0
check "DEVICE_NAME names the client" test "$(clients)" = laptop
check "Proton config passes xray -test" valid
check "Proton chain is the catch-all exit" test "$(jq -r '.routing.rules[-1].outboundTag' "$CFG")" = exit-wg
check "device file named after the device" test -f "$W/vpn-chain-laptop.env"

# ---- xray version pin ----------------------------------------------------
setup
printf '#!/bin/sh\necho "Xray 26.10.21 (Xray, Penetrates Everything.) deadbeef (go1.26 linux/amd64)"\n' > "$W/xray-other"
chmod +x "$W/xray-other"
XRAY_BIN="$W/xray-other" deploy
check "unpinned xray version refused" test "$RC" -ne 0
check "refusal names the pinned version" bash -c 'grep -q "Xray v26.3.27" <<<"$1"' _ "$ALL"
check "refused deploy writes no config" test ! -e "$CFG"

# ---- unkeepable and emptied configs --------------------------------------
setup
printf '{ "inbounds": [\n' > "$CFG"
cp "$CFG" "$W/before"
deploy
check "corrupt config refused" test "$RC" -ne 0
check "refusal names FRESH=1" bash -c 'grep -q "FRESH=1" <<<"$1"' _ "$ALL"
check "corrupt config left untouched" cmp "$CFG" "$W/before"
FRESH=1 deploy
check "FRESH=1 replaces a corrupt config" test "$RC" -eq 0

setup
jq '.inbounds[0].streamSettings.realitySettings.shortIds = []' "$FIX/today.json" > "$CFG"
cp "$CFG" "$W/before"
deploy
check "config without short IDs refused" test "$RC" -ne 0
check "config without short IDs left untouched" cmp "$CFG" "$W/before"

setup
deploy
PRIV="$(inb '.streamSettings.realitySettings.privateKey')"
bash "$SERVER/xray-clients.sh" revoke device1 --force >/dev/null 2>&1
deploy
check "re-run after revoking every device exits 0" test "$RC" -eq 0
check "re-run keeps the keypair with no devices" test "$(inb '.streamSettings.realitySettings.privateKey')" = "$PRIV"
check "re-run leaves no clients" test "$(jq '.inbounds[0].settings.clients | length' "$CFG")" -eq 0

setup
deploy
cat > "$W/stats.json" <<'JSON'
{"stat":[{"name":"user>>>device1>>>traffic>>>uplink","value":"8192"}]}
JSON
STATS_CMD="cat $W/stats.json" deploy
check "re-run folds the counters into the sidecar before restarting" test "$(jq -r .device1.up "$SIDECAR")" = 8192

# ---- legacy config -------------------------------------------------------
setup
cp "$FIX/today.json" "$CFG"
deploy
check "legacy re-run exits 0" test "$RC" -eq 0
check "legacy client kept as client1" test "$(clients)" = client1
check "legacy uuid kept" test "$(uuid_of client1)" = 11111111-1111-4111-8111-111111111111
check "legacy short ID kept" test "$(sids)" = 0123456789abcdef
check "legacy keypair kept" test "$(inb '.streamSettings.realitySettings.privateKey')" = mJZadK8WS3D6oFxqlXypqkrdiBeHBIYIvLY2GjDo0nA
check "legacy re-run gains the stats API" test "$(jq -r '.inbounds[] | select(.tag == "api") | .port' "$CFG")" = 10085
check "legacy re-run invents no sidecar" test ! -e "$SIDECAR"
check "legacy config can then be adopted" bash -c 'bash "$1/xray-clients.sh" adopt laptop >/dev/null 2>&1' _ "$SERVER"

finish
