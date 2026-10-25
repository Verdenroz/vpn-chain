#!/usr/bin/env bash
# shellcheck disable=SC2016 # bash -c bodies expand in the child
# rotate-vless-identity.sh and vless-link-generator.py against fixtures.
# shellcheck source=server/tests/lib.sh
. "$(dirname "$0")/lib.sh"

export VPN_CHAIN_REMOTE=env

setup() {
    "$1"
    export VPN_CHAIN_SECRETS="$W/secrets.env"
    cat > "$VPN_CHAIN_SECRETS" <<'EOF'
VLESS_UUID=00000000-0000-0000-0000-000000000000
VPS_IP=203.0.113.10
REALITY_PUBKEY=PUBKEY-FIXTURE
SHORT_ID=0000000000000000
SNI=www.example.com
EOF
    chmod 600 "$VPN_CHAIN_SECRETS"
}
single() { fresh today.json; }

rot() {
    ALL="$(bash "$SERVER/rotate-vless-identity.sh" "$@" 2>&1)"
    RC=$?
}
priv() { jq -r '.inbounds[0].streamSettings.realitySettings.privateKey' "$CFG"; }

# ---- identity rotation ---------------------------------------------------
setup single
OLD_PRIV="$(priv)"
rot
check "single-device rotate exits 0" test "$RC" -eq 0
check "uuid replaced" test "$(uuid_of client1)" != 11111111-1111-4111-8111-111111111111
check "short ID replaced, still one" test "$(sids)" != 0123456789abcdef -a "$(jq '.inbounds[0].streamSettings.realitySettings.shortIds | length' "$CFG")" -eq 1
check "keypair untouched without --reality" test "$(priv)" = "$OLD_PRIV"
check "secrets.env has the new uuid" bash -c 'grep -qx "VLESS_UUID=$2" "$1"' _ "$VPN_CHAIN_SECRETS" "$(uuid_of client1)"
check "secrets.env has the new short ID" bash -c 'grep -qx "SHORT_ID=$2" "$1"' _ "$VPN_CHAIN_SECRETS" "$(sids)"
check "output never shows the uuid" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$(uuid_of client1)"
check "config still passes xray -test" valid

setup fresh_multi
cp "$CFG" "$W/before"; cp "$VPN_CHAIN_SECRETS" "$W/secrets-before"
rot
check "rotate refuses a multi-device relay" test "$RC" -ne 0
check "refusal points at devices.sh rekey" bash -c 'grep -q "devices.sh rekey" <<<"$1"' _ "$ALL"
check "refusal leaves the config byte-identical" cmp "$CFG" "$W/before"
check "refusal leaves secrets.env byte-identical" cmp "$VPN_CHAIN_SECRETS" "$W/secrets-before"

setup single
echo '{}' > "$SIDECAR"
rot
check "rotate refuses once a sidecar exists" test "$RC" -ne 0

# ---- --reality -----------------------------------------------------------
setup fresh_multi
OLD_PRIV="$(priv)"
cp "$CFG" "$W/before"
rot --reality
check "--reality exits 0 on a multi-device relay" test "$RC" -eq 0
check "--reality replaced the private key" test "$(priv)" != "$OLD_PRIV"
check "--reality left clients alone" test "$(jq -c '.inbounds[0].settings' "$CFG")" = "$(jq -c '.inbounds[0].settings' "$W/before")"
check "--reality left short IDs alone" test "$(jq -c '.inbounds[0].streamSettings.realitySettings.shortIds' "$CFG")" = "$(jq -c '.inbounds[0].streamSettings.realitySettings.shortIds' "$W/before")"
check "secrets.env REALITY_PUBKEY matches the new key" bash -c 'grep -qx "REALITY_PUBKEY=$("$1" x25519 -i "$2" | sed -n "s/^Password (PublicKey): //p")" "$3"' _ "$XRAY" "$(priv)" "$VPN_CHAIN_SECRETS"
check "--reality leaves the uuid in secrets.env" bash -c 'grep -qx VLESS_UUID=00000000-0000-0000-0000-000000000000 "$1"' _ "$VPN_CHAIN_SECRETS"
check "--reality says every device needs the key" bash -c 'grep -q "Every device needs the new REALITY_PUBKEY" <<<"$1"' _ "$ALL"
check "--reality config passes xray -test" valid

# ---- link generator ------------------------------------------------------
link() {
    OUT_TXT="$(CONFIG="$CFG" VPS_IP=203.0.113.10 python3 -I "$SERVER/vless-link-generator.py" "$@" 2>&1)"
    RC=$?
}
export XRAY
fresh_multi
export OUT="$W/phone.link"
link phone
check "link generator exits 0" test "$RC" -eq 0
check "link generator prints only the path" test "$OUT_TXT" = "$OUT"
check "link file is 0600" test "$(stat -c %a "$OUT")" = 600
check "link carries phone's uuid and short ID" bash -c 'grep -q "vless://33333333-3333-4333-8333-333333333333@203.0.113.10:443" "$1" && grep -q "sid=bbbbbbbbbbbbbbbb" "$1"' _ "$OUT"
link phone --stdout
check "--stdout prints the link" bash -c 'grep -q "^vless://33333333" <<<"$1"' _ "$OUT_TXT"
link nobody
check "unknown device fails" test "$RC" -ne 0
fresh today.json
link client1 --stdout
check "single client without a sidecar falls back to shortIds[0]" bash -c 'grep -q "sid=0123456789abcdef" <<<"$1"' _ "$OUT_TXT"

finish
