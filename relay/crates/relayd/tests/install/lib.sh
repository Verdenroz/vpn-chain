#!/usr/bin/env bash
# shellcheck disable=SC2034 # BASE is read by run.sh
# Shared by run.sh: a docker lab of throwaway networks, systemd containers that stand in for
# fresh VPSes, and sing-box clients that dial them. Sourced; the caller sets HERE, RELAY, ROOT,
# DISTRO and SUBNET (the first three octets of the lab's public-style network).

# xray counts the documentation ranges as private, so the lab's public-style side cannot use
# them. 11.253.x is unrouted outside the lab's bridge; PRIV_SUBNET is what the relay must refuse.
addr() { echo "$SUBNET.$1"; }
NET="vpnchain-install-$DISTRO-$$"
PRIV="$NET-priv"
UP="$NET-up"
TEST_IMG=vpn-chain-test
IMG="vpnchain-install-$DISTRO-$$"
W="$(mktemp -d)"
KEYS="$W/keys"
CONTAINERS=()
mkdir -p "$KEYS"
PASS=0

case "$DISTRO" in
    debian) BASE='debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f' ;;
    ubuntu) BASE='ubuntu:24.04@sha256:24737cef17f4e22b1977df7d9a05e77c4e28b7da5b8911f8d8ccda136bf3573c' ;;
    *) echo "unknown distro $DISTRO" >&2; exit 64 ;;
esac

cleanup() {
    local name
    for name in "${CONTAINERS[@]}"; do
        docker kill "$name" >/dev/null 2>&1 || true
        docker rm -f "$name" >/dev/null 2>&1 || true
    done
    docker network rm "$NET" "$PRIV" "$UP" >/dev/null 2>&1 || true
    docker rmi -f "$IMG" >/dev/null 2>&1 || true
    rm -rf "$W"
}
finish() {
    local status=$?
    if [ "$status" -ne 0 ]; then
        echo "FAILED with exit $status" >&2
        tail -n 30 "$W/err" "$W/err-adopt" >&2 2>/dev/null || true
        docker exec "$RELAY_C" journalctl --no-pager -n 40 -u xray -u relayd -u vpn-chain-egress -u relay-privileged >&2 || true
        [ -z "${KEEP:-}" ] || { echo "KEEP is set: leaving $RELAY_C and the lab up" >&2; return; }
    fi
    cleanup
}
trap finish EXIT

step() { printf '\n== %s\n' "$*"; }
pass() { PASS=$((PASS + 1)); printf 'PASS: %s\n' "$*"; }
no() {
    if "$@"; then
        echo "unexpectedly succeeded: $*" >&2
        return 1
    fi
}
eq() {
    if [ "$1" != "$2" ]; then
        echo "expected '$2', got '$1'" >&2
        return 1
    fi
}
g() { docker exec "$RELAY_C" "$@"; }

# lab_up: the networks, the decoy and targets on them, and the resolver. The lab networks
# have public-style addresses and no route out, so the relay and the resolver also join an
# ordinary bridge, which gets the default route and so the internet.
lab_up() {
    local caddy=caddy:2.10-alpine upstream
    upstream="$(docker run --rm "$TEST_IMG" sed -n 's/^nameserver //p' /etc/resolv.conf | head -1)"
    docker network create --subnet "$(addr 0)/24" "$NET" >/dev/null
    docker network create --subnet "$PRIV_SUBNET.0/24" "$PRIV" >/dev/null
    docker network create "$UP" >/dev/null
    printf '{\n\tlocal_certs\n\tauto_https disable_redirects\n}\ndest.test {\n\trespond "dest"\n}\n' >"$W/dest.caddy"
    printf ':80 {\n\trespond "public"\n}\n' >"$W/pub.caddy"
    printf ':80 {\n\trespond "private"\n}\n' >"$W/priv.caddy"
    docker run -d --name "$NET-dest" --network "$NET" --network-alias dest.test \
        -v "$W/dest.caddy:/etc/caddy/Caddyfile:ro" "$caddy" >/dev/null
    docker run -d --name "$NET-pub" --network "$NET" --ip "$(addr 50)" --network-alias public.test \
        -v "$W/pub.caddy:/etc/caddy/Caddyfile:ro" "$caddy" >/dev/null
    docker run -d --name "$NET-priv" --network "$PRIV" --ip "$PRIV_SUBNET.50" \
        -v "$W/priv.caddy:/etc/caddy/Caddyfile:ro" "$caddy" >/dev/null
    docker run -d --name "$NET-dns" --network "$NET" --ip "$(addr 60)" \
        -v "$HERE/dns.py:/dns.py:ro" "$TEST_IMG" python3 /dns.py "$(addr 50)" "$upstream" >/dev/null
    docker network connect --gw-priority 100 "$UP" "$NET-dns"
    CONTAINERS+=("$NET-dest" "$NET-pub" "$NET-priv" "$NET-dns")
}

# relay_up NAME IP: a fresh VPS. The flags are the ones relay-jobs' live suite uses, with
# NET_ADMIN added because nft needs it; never --privileged.
relay_up() {
    local name="$1" ip="$2"
    read -ra extra <<<"${RELAY_JOBS_DOCKER_EXTRA:-}"
    CONTAINERS+=("$name")
    docker run -d --name "$name" "${extra[@]}" --network "$NET" --ip "$ip" --dns "$(addr 60)" \
        --cap-add SYS_ADMIN --cap-add NET_ADMIN --cgroupns=private \
        --tmpfs /run --tmpfs /run/lock --tmpfs /tmp \
        -v "$HERE:/opt/live:ro" -v "$BUILD:/opt/build:ro" -v "$ROOT:/repo:ro" \
        "$IMG" bash -c 'mount -o remount,rw /sys/fs/cgroup && exec /sbin/init' >/dev/null
    docker network connect "$PRIV" "$name"
    docker network connect --gw-priority 100 "$UP" "$name"
    local _
    for _ in $(seq 60); do
        case "$(docker exec "$name" systemctl is-system-running 2>/dev/null || true)" in running | degraded) return ;; esac
        sleep 1
    done
    echo "$name never booted" >&2
    return 1
}

# link_field FILE FIELD: one decoded parameter of the pairing link in FILE.
link_field() {
    python3 -I - "$1" "$2" <<'EOF'
import sys
from urllib.parse import parse_qs, urlparse
text = open(sys.argv[1]).read()
link = next(word for word in text.split() if word.startswith("vpnchain://"))
print(parse_qs(urlparse(link).query)[sys.argv[2]][0])
EOF
}

# client_up NAME VERSION HOST PORT SNI PBK SID UUID: sing-box NAME dials the relay.
client_up() {
    local name="$1" version="$2"
    jq -n --arg host "$3" --argjson port "$4" --arg sni "$5" --arg pbk "$6" --arg sid "$7" --arg uuid "$8" '{
        log: {level: "warn"},
        inbounds: [{type: "mixed", listen: "127.0.0.1", listen_port: 1080}],
        outbounds: [{type: "vless", tag: "proxy", server: $host, server_port: $port, uuid: $uuid,
            flow: "xtls-rprx-vision", network: "tcp", packet_encoding: "xudp", connect_timeout: "3s",
            tls: {enabled: true, server_name: $sni, utls: {enabled: true, fingerprint: "chrome"},
                reality: {enabled: true, public_key: $pbk, short_id: $sid}}}]}' >"$W/$name.json"
    docker rm -f "$NET-$name" >/dev/null 2>&1 || true
    CONTAINERS+=("$NET-$name")
    docker run -d --name "$NET-$name" --network "$NET" -v "$SING_BOX/sing-box-$version:/opt/sb:ro" \
        -v "$W/$name.json:/c.json:ro" "$TEST_IMG" /opt/sb/sing-box run -c /c.json >/dev/null
    sleep 2
}

# client_from CLIENT VERSION LINK_FILE [UUID SID]: a client with the identity in the link, or
# with the given one.
client_from() {
    local name="$1" version="$2" file="$3"
    client_up "$name" "$version" "$(link_field "$file" h)" "$(link_field "$file" p)" "$(link_field "$file" sni)" \
        "$(link_field "$file" pbk)" "${5:-$(link_field "$file" sid)}" "${4:-$(link_field "$file" id)}"
}

# through CLIENT URL [curl option...]: the answer through the relay, or nothing.
through() {
    local name="$1" url="$2"
    shift 2
    docker exec "$NET-$name" curl -sS --max-time 8 --socks5-hostname 127.0.0.1:1080 "$@" "$url" 2>/dev/null || true
}

# new_key NAME: a P-256 key pair, as the app's management key.
new_key() {
    openssl ecparam -name prime256v1 -genkey -noout -out "$KEYS/$1.pem" 2>/dev/null
}

public_key_b64() { openssl pkey -in "$KEYS/$1.pem" -pubout -outform DER 2>/dev/null | base64 -w0; }

# enroll CLIENT TOKEN KEY: POST /v1/enroll through the tunnel; prints the status, then the body.
enroll() {
    local body out
    body="$(jq -nc --arg token "$2" --arg key "$(public_key_b64 "$3")" '{token: $token, publicKey: $key}')"
    out="$(through "$1" http://agent.vpn-chain.invalid/v1/enroll -X POST -H 'Content-Type: application/json' \
        -d "$body" -w '\n%{http_code}')"
    printf '%s %s' "${out##*$'\n'}" "${out%$'\n'*}"
}

# signed_full CLIENT KEY KEY_ID METHOD PATH [BODY]: a signed request through the tunnel; prints
# the status, then the body.
signed_full() {
    local body="${6:-}" sha ts nonce signature out
    sha="$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)"
    ts="$(date +%s)"
    nonce="$(openssl rand -hex 16)"
    signature="$(printf 'vpn-chain-relay-v1\n%s\n%s\n%s\n%s\n%s' "$4" "$5" "$sha" "$ts" "$nonce" \
        | openssl dgst -sha256 -sign "$KEYS/$2.pem" | base64 -w0)"
    out="$(through "$1" "http://agent.vpn-chain.invalid$5" -X "$4" -w '\n%{http_code}' --data-binary "$body" \
        -H 'Content-Type: application/json' -H "X-Key-Id: $3" -H "X-Timestamp: $ts" -H "X-Nonce: $nonce" \
        -H "X-Signature: $signature")"
    printf '%s %s' "${out##*$'\n'}" "${out%$'\n'*}"
}

# signed CLIENT KEY KEY_ID METHOD PATH [BODY]: the status of a signed request.
signed() { signed_full "$@" | cut -d' ' -f1; }
