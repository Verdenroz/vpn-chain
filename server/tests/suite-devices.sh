#!/usr/bin/env bash
# shellcheck disable=SC2016 # bash -c bodies expand in the child
# devices.sh driven against xray-clients.sh through the transport override.
# shellcheck source=server/tests/lib.sh
. "$(dirname "$0")/lib.sh"

export VPN_CHAIN_REMOTE=env

setup() {
    fresh_multi
    export VPN_CHAIN_SECRETS="$W/secrets.env" VPN_CHAIN_DEVICES_DIR="$W/devices"
    cat > "$VPN_CHAIN_SECRETS" <<'EOF'
VLESS_UUID=00000000-0000-0000-0000-000000000000
VPS_IP=203.0.113.10
REALITY_PUBKEY=PUBKEY-FIXTURE
SHORT_ID=0000000000000000
SNI=www.example.com
SERVER_PORT=443
ENTRY_PRIVKEY=ENTRY-SECRET-FIXTURE
ENTRY_HOST=198.51.100.7
EOF
    chmod 600 "$VPN_CHAIN_SECRETS"
}

dev() {
    ALL="$(bash "$SERVER/devices.sh" "$@" 2>&1)"
    RC=$?
}

# ---- add, default file ---------------------------------------------------
setup
dev add watch
F="$VPN_CHAIN_DEVICES_DIR/watch.env"
NEW_UUID="$(uuid_of watch)"
NEW_SID="$(jq -r .watch.shortId "$SIDECAR")"
check "add exits 0" test "$RC" -eq 0
check "device file exists" test -f "$F"
check "device file is 0600" test "$(stat -c %a "$F")" = 600
check "devices dir is 0700" test "$(stat -c %a "$VPN_CHAIN_DEVICES_DIR")" = 700
check "device file carries the new identity" bash -c 'grep -qx "VLESS_UUID=$2" "$1" && grep -qx "SHORT_ID=$3" "$1" && grep -qx DEVICE_NAME=watch "$1"' _ "$F" "$NEW_UUID" "$NEW_SID"
check "device file carries the shared relay keys" bash -c 'grep -qx VPS_IP=203.0.113.10 "$1" && grep -qx REALITY_PUBKEY=PUBKEY-FIXTURE "$1" && grep -qx SNI=www.example.com "$1" && grep -qx SERVER_PORT=443 "$1"' _ "$F"
check "device file has no ENTRY_ keys" bash -c '! grep -q ENTRY "$1"' _ "$F"
check "terminal output never shows the uuid" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$NEW_UUID"
check "terminal output never shows the short ID" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$NEW_SID"
check "secrets.env untouched by add" bash -c 'grep -qx VLESS_UUID=00000000-0000-0000-0000-000000000000 "$1"' _ "$VPN_CHAIN_SECRETS"

# ---- add --out / --self --------------------------------------------------
setup
dev add watch --out "$W/elsewhere.env"
check "--out writes the given path" test -f "$W/elsewhere.env"
check "--out leaves the default dir alone" test ! -e "$VPN_CHAIN_DEVICES_DIR"
check "--out file is 0600" test "$(stat -c %a "$W/elsewhere.env")" = 600

setup
dev add watch --self
check "--self exits 0" test "$RC" -eq 0
check "--self rewrites VLESS_UUID" bash -c 'grep -qx "VLESS_UUID=$2" "$1"' _ "$VPN_CHAIN_SECRETS" "$(uuid_of watch)"
check "--self adds DEVICE_NAME" bash -c 'grep -qx DEVICE_NAME=watch "$1"' _ "$VPN_CHAIN_SECRETS"
check "--self keeps ENTRY_ keys" bash -c 'grep -qx ENTRY_PRIVKEY=ENTRY-SECRET-FIXTURE "$1" && grep -qx ENTRY_HOST=198.51.100.7 "$1"' _ "$VPN_CHAIN_SECRETS"
check "--self keeps a backup" test -f "$VPN_CHAIN_SECRETS.bak"
check "--self leaves secrets.env 0600" test "$(stat -c %a "$VPN_CHAIN_SECRETS")" = 600
check "--self writes no device file" test ! -e "$VPN_CHAIN_DEVICES_DIR"
check "--self output never shows the uuid" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$(uuid_of watch)"

setup
dev add watch --self --out "$W/x.env"
check "--self with --out refused" test "$RC" -ne 0

# ---- failures leave local files alone ------------------------------------
setup
cp "$CFG" "$W/before"; cp "$VPN_CHAIN_SECRETS" "$W/secrets-before"
ACTIVE_CMD=false dev add watch --self
check "failed restart exits non-zero" test "$RC" -ne 0
check "failed restart leaves secrets.env byte-identical" cmp "$VPN_CHAIN_SECRETS" "$W/secrets-before"
check "failed restart leaves the config byte-identical" cmp "$CFG" "$W/before"
ACTIVE_CMD=false dev add watch
check "failed restart writes no device file" test ! -e "$VPN_CHAIN_DEVICES_DIR/watch.env"

setup
sed -i '/^REALITY_PUBKEY=/d' "$VPN_CHAIN_SECRETS"
cp "$CFG" "$W/before"
dev add watch
check "missing REALITY_PUBKEY refused" test "$RC" -ne 0
check "missing key refused before the server is touched" cmp "$CFG" "$W/before"

# ---- rekey / revoke / rename / list --------------------------------------
setup
dev rekey phone
check "rekey exits 0" test "$RC" -eq 0
check "rekey device file has the new uuid" bash -c 'grep -qx "VLESS_UUID=$2" "$1"' _ "$VPN_CHAIN_DEVICES_DIR/phone.env" "$(uuid_of phone)"
check "rekey output never shows the uuid" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$(uuid_of phone)"

dev revoke phone
check "revoke exits 0" test "$RC" -eq 0
check "revoke removed the client" test "$(clients)" = "laptop,tablet"
dev revoke phone
check "revoking twice still exits 0" test "$RC" -eq 0
dev revoke laptop; dev revoke tablet
check "last device refused without --force" test "$RC" -ne 0
dev revoke tablet --force
check "--force revokes the last device" test "$RC" -eq 0

setup
dev rename phone pixel
check "rename goes through" test "$(clients)" = "laptop,pixel,tablet"
dev list
check "list goes through" bash -c 'grep -q "^laptop " <<<"$1"' _ "$ALL"

# ---- secrets handling ----------------------------------------------------
setup
mkdir "$W/bin"
cat > "$W/bin/mv" <<'WRAP'
#!/usr/bin/env bash
stat -c %a "$1" >> "$MVLOG"
exec /usr/bin/mv "$@"
WRAP
chmod +x "$W/bin/mv"
export MVLOG="$W/mvlog"
umask 022
PATH="$W/bin:$PATH" dev add watch --self
umask 077
check "--self exits 0 under a lax umask" test "$RC" -eq 0
check "every file moved into place was 0600" bash -c 'test "$(wc -l < "$1")" -ge 3 && ! grep -qv "^600$" "$1"' _ "$MVLOG"
check "secrets.env backup is 0600" test "$(stat -c %a "$VPN_CHAIN_SECRETS.bak")" = 600

setup
cp "$CFG" "$W/before"
dev add watch --out "$W/no-such-dir/x.env"
check "unwritable destination refused" test "$RC" -ne 0
check "unwritable destination refused before the server changed" cmp "$CFG" "$W/before"

setup
mkdir "$W/bin"
cat > "$W/bin/mv" <<'WRAP'
#!/usr/bin/env bash
case "${@: -1}" in *.env) exit 1 ;; esac
exec /usr/bin/mv "$@"
WRAP
chmod +x "$W/bin/mv"
PATH="$W/bin:$PATH" dev add watch --out "$W/late.env"
check "late local failure exits non-zero" test "$RC" -ne 0
check "late failure prints the recovery command" bash -c 'grep -q "devices.sh rekey watch --out $2" <<<"$1"' _ "$ALL" "$W/late.env"
check "late failure never prints the identity" bash -c '! grep -q "$2" <<<"$1"' _ "$ALL" "$(uuid_of watch)"
check "late failure leaves no temp file" bash -c '! compgen -G "$1/late.env.*"' _ "$W"

finish
