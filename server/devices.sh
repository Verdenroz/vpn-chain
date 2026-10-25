#!/usr/bin/env bash
# devices.sh — manage per-device relay identities on the VPS and keep the new
# credentials off the terminal: they are generated on the server, captured into a
# shell variable here, and written straight to a 0600 file. Never echoed.
#
# Usage:
#   server/devices.sh add NAME    [--self|--out PATH] [user@vps]
#   server/devices.sh rekey NAME  [--self|--out PATH] [user@vps]
#   server/devices.sh revoke NAME [--force] [user@vps]
#   server/devices.sh rename OLD NEW | adopt NAME | list   [user@vps]
#
# add/rekey write ~/.config/vpn-chain/devices/NAME.env by default; --self updates
# the local secrets.env in place; --out writes elsewhere. The device file carries
# the shared relay keys, never the ENTRY_* hop keys.
# VPN_CHAIN_REMOTE replaces "ssh -i KEY TARGET" (e.g. "docker exec -i NAME").
#
# Requires jq + xray + openssl + flock on the VPS.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${VPN_CHAIN_SECRETS:-$HOME/.config/vpn-chain/secrets.env}"
DEVICES_DIR="${VPN_CHAIN_DEVICES_DIR:-$HOME/.config/vpn-chain/devices}"
SSH_KEY="${VPN_CHAIN_SSH_KEY:-$HOME/.ssh/id_1984_relay}"
log() { printf '\033[1;36m[devices]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[devices]\033[0m %s\n' "$*" >&2; exit 1; }

CMD="${1:-}"
[ $# -gt 0 ] && shift
TARGET="" OUT="" SELF=0 FORCE=""
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --self) SELF=1 ;;
        --out) OUT="${2:?--out needs a path}"; shift ;;
        --force) FORCE="--force" ;;
        *@*) TARGET="$1" ;;
        *) ARGS+=("$1") ;;
    esac
    shift
done
[ "$SELF" = 0 ] || [ -z "$OUT" ] || die "--self and --out are exclusive"

remote() {
    local -a t
    if [ -n "${VPN_CHAIN_REMOTE:-}" ]; then
        read -ra t <<<"$VPN_CHAIN_REMOTE"
    else
        # shellcheck source=/dev/null
        [ -f "$SECRETS_FILE" ] && . "$SECRETS_FILE"
        TARGET="${TARGET:-root@${VPS_IP:-}}"
        [ -n "${TARGET#root@}" ] || die "no VPS_IP in secrets and no target given"
        t=(ssh -i "$SSH_KEY" -o ConnectTimeout=12 "$TARGET")
    fi
    "${t[@]}" bash -s -- "$@" < "$HERE/xray-clients.sh"
}

upd() {
    local k="$1" v="$2"
    awk -v k="$k" -v v="$v" '
        $0 ~ "^" k "=" { print k "=" v; found=1; next }
        { print }
        END { if (!found) print k "=" v }
    ' "$SECRETS_FILE" > "${SECRETS_FILE}.tmp" && mv "${SECRETS_FILE}.tmp" "$SECRETS_FILE"
}

write_self() {
    cp "$SECRETS_FILE" "${SECRETS_FILE}.bak"
    upd VLESS_UUID "$1"
    upd SHORT_ID "$2"
    upd DEVICE_NAME "$3"
    chmod 600 "$SECRETS_FILE"
    log "Updated $SECRETS_FILE (backup: ${SECRETS_FILE}.bak)."
}

write_device_file() {
    local dest="$1" tmp
    if [ -z "$OUT" ]; then install -d -m 700 "$DEVICES_DIR"; fi
    tmp="$(mktemp "$dest.XXXXXX")"
    {
        grep -E '^(VPS_IP|REALITY_PUBKEY|SNI|SERVER_PORT)=' "$SECRETS_FILE" || true
        printf 'VLESS_UUID=%s\nSHORT_ID=%s\nDEVICE_NAME=%s\n' "$2" "$3" "$4"
    } > "$tmp"
    chmod 600 "$tmp"; mv "$tmp" "$dest"
    log "Wrote $dest."
}

# issue CMD NAME: run add/rekey remotely and deliver the identity locally.
issue() {
    local name="$1" new uuid sid k
    [ -f "$SECRETS_FILE" ] || die "secrets file not found: $SECRETS_FILE"
    for k in VPS_IP REALITY_PUBKEY SNI; do
        grep -q "^$k=." "$SECRETS_FILE" || die "$k missing from $SECRETS_FILE"
    done
    new="$(remote "$CMD" "$name")"
    uuid="$(printf '%s\n' "$new" | sed -n 's/^UUID=//p')"
    sid="$(printf '%s\n' "$new" | sed -n 's/^SHORT_ID=//p')"
    [ -n "$uuid" ] && [ -n "$sid" ] || die "server did not return a complete identity"
    if [ "$SELF" = 1 ]; then
        write_self "$uuid" "$sid" "$name"
    else
        write_device_file "${OUT:-$DEVICES_DIR/$name.env}" "$uuid" "$sid" "$name"
    fi
}

[ "${#ARGS[@]}" -ge 1 ] || [ "$CMD" = list ] || die "usage: devices.sh add|rekey|revoke|rename|adopt|list ..."
case "$CMD" in
    add|rekey) issue "${ARGS[0]}" ;;
    revoke) remote revoke "${ARGS[@]}" ${FORCE:+"$FORCE"} ;;
    rename|adopt) remote "$CMD" "${ARGS[@]}" ;;
    list) remote list ;;
    *) die "unknown command: $CMD" ;;
esac
