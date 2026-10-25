#!/usr/bin/env bash
# shellcheck disable=SC2016 # bash -c bodies expand in the child
# rotate-exit-key.sh: the new WireGuard key must reach the config without touching any argv.
# shellcheck source=server/tests/lib.sh
. "$(dirname "$0")/lib.sh"

fresh today.json
OLD_KEY="$(openssl rand -base64 32)"
NEW_KEY="$(openssl rand -base64 32)"
PEER="$(openssl rand -base64 32)"
jq --arg sk "$OLD_KEY" --arg pk "$PEER" '.outbounds += [{tag: "exit-wg", protocol: "wireguard",
    settings: {secretKey: $sk, address: ["10.2.0.2/32"], mtu: 1280,
        peers: [{publicKey: $pk, endpoint: "198.51.100.9:51820", allowedIPs: ["0.0.0.0/0"]}]}}]' \
    "$FIX/today.json" > "$CFG"

cat > "$W/new.conf" <<EOF
[Interface]
PrivateKey = $NEW_KEY
Address = 10.2.0.3/32
[Peer]
PublicKey = $PEER
Endpoint = 198.51.100.10:51820
EOF
cat > "$W/rec" <<'WRAP'
#!/usr/bin/env bash
printf '%s\n' "$@" >> "$ARGV_LOG"
exec "$@"
WRAP
chmod +x "$W/rec"
export ARGV_LOG="$W/argv" VPN_CHAIN_REMOTE="$W/rec" VPN_CHAIN_SECRETS="$W/secrets.env"
printf 'VPS_IP=203.0.113.10\n' > "$VPN_CHAIN_SECRETS"

ALL="$(bash "$SERVER/rotate-exit-key.sh" "$W/new.conf" 2>&1)"
RC=$?
check "rotate-exit-key exits 0" test "$RC" -eq 0
check "the wrapper saw the remote command" test -s "$ARGV_LOG"
check "the private key is in no argv" bash -c '! grep -qF "$2" "$1"' _ "$ARGV_LOG" "$NEW_KEY"
check "the private key is not printed" bash -c '! grep -qF "$2" <<<"$1"' _ "$ALL" "$NEW_KEY"
check "the config gets the new key" test "$(jq -r '.outbounds[] | select(.tag == "exit-wg") | .settings.secretKey' "$CFG")" = "$NEW_KEY"
check "the config gets the new endpoint" test "$(jq -r '.outbounds[] | select(.tag == "exit-wg") | .settings.peers[0].endpoint' "$CFG")" = 198.51.100.10:51820
check "the config still passes xray -test" valid

finish
