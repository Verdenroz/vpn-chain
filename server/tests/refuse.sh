#!/usr/bin/env bash
# shellcheck disable=SC2016 # remote command bodies expand in the container
# A relay deployed by deploy-server.sh must refuse every loopback and private target a
# REALITY-authenticated sing-box asks for, whether it names an address or a name that
# resolves to one, and still reach a public-style address. Throwaway docker networks: one of
# ordinary public addresses, one of private ones. The relay itself is deployed in a container.
# REFUSE_XRAY_VERSION picks the relay (default: the pin); SINGBOX_VERSIONS the clients.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=server/lib/pins.sh
. "$HERE/../lib/pins.sh"
ROOT="$(cd "$HERE/../.." && pwd)"
SINGBOX_VERSIONS="${SINGBOX_VERSIONS:-$SINGBOX_VERSIONS_DEFAULT}"
XRAY_DIR="$(dirname "$("$HERE/fetch-xray.sh" ${REFUSE_XRAY_VERSION:+"$REFUSE_XRAY_VERSION"})")"
PUB=vpnchain-refuse-pub PRIV=vpnchain-refuse-priv
PUB_NET=11.253.77 PRIV_NET=198.19.77
W="$(mktemp -d)"
PASS=0 FAIL=0
IMG=vpn-chain-test

drop() {
    docker rm -f refuse-srv refuse-dest refuse-dns refuse-pub refuse-priv refuse-sb >/dev/null 2>&1 || true
    docker network rm "$PUB" "$PRIV" >/dev/null 2>&1 || true
}
trap 'drop; rm -rf "$W"' EXIT
drop

check() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then PASS=$((PASS + 1)); else FAIL=$((FAIL + 1)); printf 'FAIL: %s\n' "$desc"; fi
}

docker build -q -t "$IMG" -f "$HERE/Dockerfile" "$HERE" >/dev/null
docker network create --subnet "$PUB_NET.0/24" "$PUB" >/dev/null
docker network create --subnet "$PRIV_NET.0/24" "$PRIV" >/dev/null

cat > "$W/Caddyfile" <<'EOF'
{
	local_certs
	auto_https disable_redirects
}
dest.test {
	respond "dest"
}
EOF
docker run -d --name refuse-dest --network "$PUB" --network-alias dest -v "$W/Caddyfile":/etc/caddy/Caddyfile:ro caddy:2.10-alpine >/dev/null
printf ':80 {\n\trespond "%s"\n}\n' public > "$W/pub.caddy"
printf ':80 {\n\trespond "%s"\n}\n' private > "$W/priv.caddy"
docker run -d --name refuse-pub --network "$PUB" --ip "$PUB_NET.50" -v "$W/pub.caddy":/etc/caddy/Caddyfile:ro caddy:2.10-alpine >/dev/null
docker run -d --name refuse-priv --network "$PRIV" --ip "$PRIV_NET.50" -v "$W/priv.caddy":/etc/caddy/Caddyfile:ro caddy:2.10-alpine >/dev/null

# A resolver that answers a public address once per name and 127.0.0.1 after, to catch a relay that checks one lookup and dials another.
docker run -d --name refuse-dns --network "$PUB" --ip "$PUB_NET.60" -v "$HERE/rebind-dns.py":/rebind-dns.py:ro "$IMG" \
    python3 /rebind-dns.py "$PUB_NET.50" >/dev/null

# The relay: names resolve as a hostile client would want them to, and web servers listen on its loopback.
RELOAD='pkill -x xray; sleep 0.3; setsid nohup /opt/xray/xray run -config /usr/local/etc/xray/config.json </dev/null >/var/log/xray/out.log 2>&1 &'
docker run -d --name refuse-srv --network "$PUB" --ip "$PUB_NET.10" --dns "$PUB_NET.60" --init \
    --add-host "loop.test:127.0.0.1" --add-host "public.test:$PUB_NET.50" --add-host "private.test:$PRIV_NET.50" \
    -v "$ROOT":/repo:ro -v "$XRAY_DIR":/opt/xray:ro "$IMG" sleep infinity >/dev/null
docker network connect "$PRIV" refuse-srv
docker exec -d refuse-srv sh -c 'mkdir /srv/loop && echo loopback > /srv/loop/index.html && cd /srv/loop && (python3 -m http.server 80 --bind 127.0.0.1 &) && exec python3 -m http.server 8081 --bind 127.0.0.1'
docker exec refuse-srv env DEPLOY_SKIP_INSTALL=1 XRAY_BIN=/opt/xray/xray VPS_IP=srv SNI=dest.test DEST=dest:443 \
    SETTLE=1 RELOAD_CMD="$RELOAD" ACTIVE_CMD='pgrep -x xray' DEVICE_FILE_DIR=/root \
    bash /repo/server/deploy-server.sh >/dev/null 2>&1 || { echo "deploy-server.sh failed in the container" >&2; exit 1; }

env_of() { docker exec refuse-srv sed -n "s/^$1=//p" /root/vpn-chain-device1.env; }
UUID="$(env_of VLESS_UUID)" SID="$(env_of SHORT_ID)" PBK="$(env_of REALITY_PUBKEY)"

sb_conf() {
    jq -n --arg uuid "$UUID" --arg sid "$SID" --arg pbk "$PBK" --arg srv "$PUB_NET.10" '{
        log: {level: "warn"},
        inbounds: [{type: "mixed", listen: "127.0.0.1", listen_port: 1080}],
        outbounds: [{type: "vless", tag: "proxy", server: $srv, server_port: 443, uuid: $uuid,
            flow: "xtls-rprx-vision", network: "tcp", packet_encoding: "xudp", connect_timeout: "3s",
            tls: {enabled: true, server_name: "dest.test", utls: {enabled: true, fingerprint: "chrome"},
                reality: {enabled: true, public_key: $pbk, short_id: $sid}}}]}'
}

# fetch TARGET [curl option...]: what the target answers through the relay, or nothing.
fetch() {
    local target="$1"; shift
    docker exec refuse-sb curl -sS --max-time 6 --socks5-hostname 127.0.0.1:1080 "$@" "http://$target/" 2>/dev/null || true
}
reached() { [ -n "$(fetch "$@")" ]; }
refused() { ! reached "$@"; }
# An HTTP/2 prior-knowledge request answers with a status only when xray's gRPC API is reached.
api_status() { fetch "$1:10085" --http2-prior-knowledge -o /dev/null -w '%{http_code}'; }
api_refused() { [ "$(api_status "$1")" = 000 ] || [ -z "$(api_status "$1")" ]; }

for sb in $SINGBOX_VERSIONS; do
    SB_DIR="$(dirname "$("$HERE/fetch-singbox.sh" "$sb")")"
    sb_conf > "$W/sb.json"
    docker run -d --name refuse-sb --network "$PUB" -v "$SB_DIR":/opt/sb:ro -v "$W/sb.json":/c.json:ro \
        "$IMG" /opt/sb/sing-box run -c /c.json >/dev/null
    sleep 2
    check "sing-box $sb reaches a public name through the relay" test "$(fetch public.test)" = public
    check "sing-box $sb reaches a public address through the relay" test "$(fetch "$PUB_NET.50")" = public
    if [ "$(fetch public.test)" != public ]; then
        printf 'FAIL: sing-box %s cannot reach the relay, so the refusals below prove nothing\n' "$sb"
        FAIL=$((FAIL + 1)); docker rm -f refuse-sb >/dev/null; continue
    fi
    check "sing-box $sb is refused a private name" refused private.test
    check "sing-box $sb is refused a private address" refused "$PRIV_NET.50"
    check "sing-box $sb is refused a loopback address" refused 127.0.0.1:8081
    check "sing-box $sb is refused localhost" refused localhost:8081
    check "sing-box $sb is refused a name for loopback" refused loop.test:8081
    check "sing-box $sb is refused xray's API port by address" api_refused 127.0.0.1
    check "sing-box $sb is refused xray's API port by name" api_refused localhost
    if [ "$(fetch "rb-${sb//./-}.test:80")" = loopback ]; then
        printf 'KNOWN GAP: sing-box %s reached loopback through a name that changes its answer\n' "$sb"
    else
        printf 'FAIL: the rebinding gap is closed; turn this probe into a check\n'; FAIL=$((FAIL + 1))
    fi
    docker rm -f refuse-sb >/dev/null
done

check "the relay's own loopback server does answer locally" test "$(docker exec refuse-srv curl -s http://127.0.0.1:8081/)" = loopback
check "xray's API port answers an HTTP/2 request locally" bash -c '[ "$(docker exec refuse-srv curl -s -o /dev/null -w "%{http_code}" --http2-prior-knowledge http://127.0.0.1:10085/)" != 000 ]'

if [ "$FAIL" -gt 0 ]; then
    docker exec refuse-srv sh -c 'tail -n 8 /var/log/xray/out.log /var/log/xray/error.log; pgrep -a xray' || true
fi
printf 'refuse.sh: %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
