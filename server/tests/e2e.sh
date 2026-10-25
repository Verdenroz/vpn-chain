#!/usr/bin/env bash
# shellcheck disable=SC2016 # remote command bodies expand in the container
# End to end on a throwaway docker network: caddy (tls internal) is the REALITY
# dest and the plain-HTTP target, xray is the relay, one xray client per device.
# Drives devices.sh through `docker exec`, so add/revoke hit a real running xray.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XRAY_DIR="$(dirname "$("$HERE/fetch-xray.sh")")"
NET=vpnchain-e2e
W="$(mktemp -d)"
PASS=0 FAIL=0
IMG=vpn-chain-test

drop() {
    docker rm -f e2e-srv e2e-dest e2e-cli-a e2e-cli-b >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
}
trap 'drop; rm -rf "$W"' EXIT
drop

check() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then PASS=$((PASS + 1)); else FAIL=$((FAIL + 1)); printf 'FAIL: %s\n' "$desc"; fi
}

docker build -q -t "$IMG" -f "$HERE/Dockerfile" "$HERE" >/dev/null
docker network create "$NET" >/dev/null

cat > "$W/Caddyfile" <<'EOF'
{
	local_certs
	auto_https disable_redirects
}
dest.test {
	respond "dest"
}
:80 {
	respond "pong"
}
EOF
docker run -d --name e2e-dest --network "$NET" --network-alias dest -v "$W/Caddyfile":/etc/caddy/Caddyfile:ro caddy:2.10-alpine >/dev/null

KEYS="$("$XRAY_DIR/xray" x25519)"
PRIV="$(sed -n 's/^PrivateKey: *//p' <<<"$KEYS")"
PUB="$(sed -n 's/^Password (PublicKey): *//p' <<<"$KEYS")"

# Today's deploy output, with private targets allowed: the plain-HTTP target is a private address.
jq --arg priv "$PRIV" '
    .inbounds[0].streamSettings.realitySettings |= (.dest = "dest:443" | .serverNames = ["dest.test"] | .privateKey = $priv)
    | .routing.rules |= map(select(.ip != ["geoip:private"]))
    | (.outbounds[] | select(.tag == "direct")).settings = {finalRules: [{action: "allow"}]}
    | .stats = {} | .api = {tag: "api", services: ["StatsService"]}
    | .policy = {levels: {"0": {statsUserUplink: true, statsUserDownlink: true}}}
    | .inbounds += [{tag: "api", listen: "127.0.0.1", port: 10085, protocol: "dokodemo-door", settings: {address: "127.0.0.1"}}]
    | .routing.rules = [{type: "field", inboundTag: ["api"], outboundTag: "api"}] + .routing.rules' \
    "$HERE/fixtures/today.json" > "$W/server.json"

RELOAD='pkill -x xray; sleep 0.3; setsid nohup /opt/xray/xray run -config /usr/local/etc/xray/config.json </dev/null >/var/log/xray/out.log 2>&1 &'
docker run -d --name e2e-srv --network "$NET" --network-alias srv -v "$XRAY_DIR":/opt/xray:ro \
    --init -e XRAY=/opt/xray/xray -e SETTLE=1 -e ACTIVE_CMD='pgrep -x xray' \
    -e RELOAD_CMD="$RELOAD" \
    "$IMG" sleep infinity >/dev/null
docker exec -i e2e-srv sh -c 'mkdir -p /usr/local/etc/xray && cat > /usr/local/etc/xray/config.json' < "$W/server.json"
docker exec e2e-srv bash -c "$RELOAD"
sleep 1

cat > "$W/secrets.env" <<EOF
VPS_IP=srv
REALITY_PUBKEY=$PUB
SNI=dest.test
EOF
chmod 600 "$W/secrets.env"
export VPN_CHAIN_SECRETS="$W/secrets.env" VPN_CHAIN_DEVICES_DIR="$W/devices" VPN_CHAIN_REMOTE="docker exec -i e2e-srv"
dev() { "$HERE/../devices.sh" "$@" >"$W/dev.log" 2>&1 || { cat "$W/dev.log" >&2; return 1; }; }

start_client() { # name
    local f="$VPN_CHAIN_DEVICES_DIR/$1.env"
    (
        # shellcheck source=/dev/null
        . "$f"
        jq -n --arg uuid "$VLESS_UUID" --arg sid "$SHORT_ID" --arg pbk "$REALITY_PUBKEY" --arg sni "$SNI" --arg srv "$VPS_IP" '{
            log: {loglevel: "warning"},
            inbounds: [{listen: "127.0.0.1", port: 1080, protocol: "socks", settings: {udp: false}}],
            outbounds: [{protocol: "vless", settings: {vnext: [{address: $srv, port: 443,
                users: [{id: $uuid, encryption: "none", flow: "xtls-rprx-vision"}]}]},
                streamSettings: {network: "tcp", security: "reality",
                    realitySettings: {serverName: $sni, fingerprint: "chrome", publicKey: $pbk, shortId: $sid}}}]}' \
            > "$W/client-$1.json"
    )
    docker run -d --name "e2e-cli-$1" --network "$NET" -v "$XRAY_DIR":/opt/xray:ro -v "$W/client-$1.json":/c.json:ro \
        "$IMG" /opt/xray/xray run -config /c.json >/dev/null
    sleep 1
}

fetch() { docker exec "e2e-cli-$1" curl -sS --max-time 8 --socks5-hostname 127.0.0.1:1080 http://dest/; }

# The seed client stands in for a server deployed before devices existed.
dev adopt seed
dev add a
dev add b
start_client a
start_client b

check "device A reaches the target through the relay" test "$(fetch a)" = pong
check "device B reaches the target through the relay" test "$(fetch b)" = pong

dev revoke a
check "revoked device A is refused" bash -c '! docker exec e2e-cli-a curl -sS --max-time 8 --socks5-hostname 127.0.0.1:1080 http://dest/'
check "device B still works after A is revoked" test "$(fetch b)" = pong
check "a probe without a valid short ID gets the dest certificate" bash -c \
    'docker exec e2e-srv bash -c "openssl s_client -connect 127.0.0.1:443 -servername dest.test </dev/null 2>/dev/null | openssl x509 -noout -issuer" | grep -q "Caddy Local Authority"'

LIST="$(dev list; cat "$W/dev.log")"
check "list shows traffic for B" bash -c 'grep -E "^b .*up [0-9.]+[KMG]?" <<<"$1" | grep -qv "up -"' _ "$LIST"
check "list no longer shows A" bash -c '! grep -q "^a " <<<"$1"' _ "$LIST"

if [ "$FAIL" -gt 0 ]; then
    docker logs e2e-cli-b 2>&1 | tail -n 5
    docker exec e2e-srv sh -c 'tail -n 8 /var/log/xray/out.log /var/log/xray/error.log; pgrep -a xray'
fi
printf 'e2e.sh: %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
