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
LOG_TAG=devices
# shellcheck source=server/lib/local.sh
. "$HERE/lib/local.sh"
DEVICES_DIR="${VPN_CHAIN_DEVICES_DIR:-$HOME/.config/vpn-chain/devices}"

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
    resolve_remote "$TARGET"
    { cat "$HERE/lib/xray-common.sh"; cat "$HERE/xray-clients.sh"; } | "${REMOTE[@]}" bash -s -- "$@"
}

TMP=""
trap 'rm -f "$TMP"' EXIT

# prepare_local NAME: prove the destination is writable before the server changes.
prepare_local() {
    if [ "$SELF" = 1 ]; then
        [ -w "$SECRETS_FILE" ] || die "cannot write $SECRETS_FILE"
        cp "$SECRETS_FILE" "${SECRETS_FILE}.bak"
        TMP="${SECRETS_FILE}.tmp"
        : > "$TMP"
        DEST="$SECRETS_FILE"
    else
        DEST="${OUT:-$DEVICES_DIR/$1.env}"
        [ -n "$OUT" ] || install -d -m 700 "$DEVICES_DIR"
        TMP="$(mktemp "$DEST.XXXXXX")"
    fi
}

# deliver NAME UUID SID: every step chains with && because the caller tests the result.
deliver() {
    if [ "$SELF" = 1 ]; then
        upd "$SECRETS_FILE" VLESS_UUID "$2" && upd "$SECRETS_FILE" SHORT_ID "$3" \
            && upd "$SECRETS_FILE" DEVICE_NAME "$1" && chmod 600 "$SECRETS_FILE"
    else
        { grep -E '^(VPS_IP|REALITY_PUBKEY|SNI|SERVER_PORT)=' "$SECRETS_FILE" || true
          printf 'VLESS_UUID=%s\nSHORT_ID=%s\nDEVICE_NAME=%s\n' "$2" "$3" "$1"; } > "$TMP" \
            && chmod 600 "$TMP" && mv "$TMP" "$DEST"
    fi
}

# issue NAME: run add/rekey remotely and deliver the identity locally.
issue() {
    local name="$1" new uuid sid k
    [ -f "$SECRETS_FILE" ] || die "secrets file not found: $SECRETS_FILE"
    for k in VPS_IP REALITY_PUBKEY SNI; do
        grep -q "^$k=." "$SECRETS_FILE" || die "$k missing from $SECRETS_FILE"
    done
    prepare_local "$name"
    new="$(remote "$CMD" "$name")"
    uuid="$(printf '%s\n' "$new" | sed -n 's/^UUID=//p')"
    sid="$(printf '%s\n' "$new" | sed -n 's/^SHORT_ID=//p')"
    [ -n "$uuid" ] && [ -n "$sid" ] || die "server did not return a complete identity"
    deliver "$name" "$uuid" "$sid" \
        || die "server updated but the local write failed; reissue with: devices.sh rekey $name$([ "$SELF" = 1 ] && echo ' --self')${OUT:+ --out $OUT}"
    log "Wrote $DEST."
}

[ "${#ARGS[@]}" -ge 1 ] || [ "$CMD" = list ] || die "usage: devices.sh add|rekey|revoke|rename|adopt|list ..."
case "$CMD" in
    add|rekey) issue "${ARGS[0]}" ;;
    revoke) remote revoke "${ARGS[@]}" ${FORCE:+"$FORCE"} ;;
    rename|adopt) remote "$CMD" "${ARGS[@]}" ;;
    list) remote list ;;
    *) die "unknown command: $CMD" ;;
esac
