#!/usr/bin/env bash
# rotate-vless-identity.sh — rotate the relay's client-facing identity on the VPS
# and update the LOCAL secrets file to match, WITHOUT the new secrets ever passing
# through stdout. Safe to run even when an assistant can see your terminal.
#
# Rotates the VLESS UUID + REALITY short_id of a relay with exactly one device
# (several devices: `devices.sh rekey NAME`). With --reality it regenerates only
# the shared REALITY x25519 keypair instead, which every device must then pick
# up. The Proton exit chain is left untouched.
#
# How the secrets stay hidden: the new values are generated ON THE SERVER. Human
# status is printed to stderr; the machine-readable identity goes to stdout, which
# is captured into a shell variable here and written straight into
# ~/.config/vpn-chain/secrets.env — never echoed.
#
# Usage:
#   server/rotate-vless-identity.sh [--reality] [user@vps]
#     --reality   regenerate the REALITY keypair only (updates REALITY_PUBKEY)
#
# After it finishes:  vpn-chain down && vpn-chain up
#
# Requires jq + xray + openssl on the VPS. VPN_CHAIN_REMOTE replaces
# "ssh TARGET". VPN_CHAIN_SSH_KEY picks the key; otherwise ssh uses its own config.
set -euo pipefail

ROTATE_REALITY=0
SSH_TARGET=""
for a in "$@"; do
    case "$a" in
        --reality) ROTATE_REALITY=1 ;;
        *) SSH_TARGET="$a" ;;
    esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_TAG=vless-rotate
# shellcheck source=server/lib/local.sh
. "$HERE/lib/local.sh"

[ -f "$SECRETS_FILE" ] || die "secrets file not found: $SECRETS_FILE"
resolve_remote "$SSH_TARGET"

log "Rotating $([ "$ROTATE_REALITY" = 1 ] && echo 'REALITY keypair' || echo 'VLESS identity') on ${SSH_TARGET:-the VPS}..."

# Remote: generate + apply new identity, restart xray. Status -> stderr (visible),
# client-side values -> stdout (captured below, never displayed).
NEW="$({ cat "$HERE/lib/xray-common.sh"; cat <<'REMOTE'
lock
check_config
load
fold_stats

PUB=""
if [ "${ROTATE_REALITY:-0}" = "1" ]; then
    KP="$("$XRAY" x25519)"
    PRIV="$(printf '%s\n' "$KP" | sed -n 's/.*[Pp]rivate[Kk]ey:[[:space:]]*//p' | head -n1)"
    PUB="$(printf '%s\n'  "$KP" | sed -n 's/.*[Pp]ublic[Kk]ey:[[:space:]]*//p'  | head -n1)"
    [ -n "$PRIV" ] || PRIV="$(printf '%s\n' "$KP" | sed -n '1s/.*:[[:space:]]*//p')"
    [ -n "$PUB"  ] || PUB="$(printf '%s\n'  "$KP" | sed -n '2s/.*:[[:space:]]*//p')"
    [ -n "$PRIV" ] && [ -n "$PUB" ] || die "failed to parse new REALITY keypair"
    NEW_CFG="$(jq --arg tag "$TAG" --arg priv "$PRIV" \
        '(.inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.privateKey) = $priv' "$CFG")"
else
    names="$(jq -r --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .settings.clients[].email' "$CFG")"
    [ "$(printf '%s\n' "$names" | grep -c .)" -eq 1 ] || die "this relay has several devices; rotate one with: devices.sh rekey NAME"
    if [ "$SC" != '{}' ]; then
        jq -e --arg n "$names" 'has($n)' <<<"$SC" >/dev/null || die "client $names is not in devices.json; run: devices.sh adopt NAME"
    fi
    UUID="$("$XRAY" uuid)"
    SHORT_ID="$(openssl rand -hex 8)"
    [ "$SC" = '{}' ] || SC="$(jq -c --arg n "$names" --arg sid "$SHORT_ID" '.[$n].shortId = $sid' <<<"$SC")"
    NEW_CFG="$(jq --arg tag "$TAG" --arg id "$UUID" --arg sid "$SHORT_ID" \
        '(.inbounds[] | select(.tag == $tag)) |= (.settings.clients[0].id = $id | .streamSettings.realitySettings.shortIds = [$sid])' "$CFG")"
fi

say "applying new $([ "${ROTATE_REALITY:-0}" = 1 ] && echo 'REALITY keypair' || echo 'UUID + short_id')..."
swap_config "$NEW_CFG"
say "xray restarted with new identity."

# ONLY the client-side values reach stdout (captured locally, never printed).
if [ -n "$PUB" ]; then
    printf 'REALITY_PUBKEY=%s\n' "$PUB"
else
    printf 'UUID=%s\n' "$UUID"
    printf 'SHORT_ID=%s\n' "$SHORT_ID"
fi
REMOTE
} | "${REMOTE[@]}" env "ROTATE_REALITY=$ROTATE_REALITY" bash -s)"

# Parse the captured identity WITHOUT echoing it.
NEW_UUID="$(printf '%s\n' "$NEW" | sed -n 's/^UUID=//p')"
NEW_SHORT_ID="$(printf '%s\n' "$NEW" | sed -n 's/^SHORT_ID=//p')"
NEW_PUB="$(printf '%s\n' "$NEW" | sed -n 's/^REALITY_PUBKEY=//p')"
if [ "$ROTATE_REALITY" = 1 ]; then
    [ -n "$NEW_PUB" ] || die "server did not return a public key; secrets.env left unchanged"
else
    [ -n "$NEW_UUID" ] && [ -n "$NEW_SHORT_ID" ] \
        || die "server did not return a complete identity; secrets.env left unchanged"
fi

cp "$SECRETS_FILE" "${SECRETS_FILE}.bak"
if [ "$ROTATE_REALITY" = 1 ]; then
    upd "$SECRETS_FILE" REALITY_PUBKEY "$NEW_PUB"
else
    upd "$SECRETS_FILE" VLESS_UUID "$NEW_UUID"
    upd "$SECRETS_FILE" SHORT_ID "$NEW_SHORT_ID"
fi
chmod 600 "$SECRETS_FILE"

log "Server rotated; $SECRETS_FILE updated (backup: ${SECRETS_FILE}.bak)."
[ "$ROTATE_REALITY" = 1 ] && log "Every device needs the new REALITY_PUBKEY, not just this one."
log "Now re-render + reconnect:  vpn-chain down && vpn-chain up"
