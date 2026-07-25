#!/usr/bin/env bash
# rotate-vless-identity.sh — rotate the relay's client-facing identity on the VPS
# and update the LOCAL secrets file to match, WITHOUT the new secrets ever passing
# through stdout. Safe to run even when an assistant can see your terminal.
#
# Rotates the VLESS UUID + REALITY short_id (and, with --reality, the REALITY
# x25519 keypair). The Proton exit chain is left untouched.
#
# How the secrets stay hidden: the new values are generated ON THE SERVER. Human
# status is printed to stderr; the machine-readable identity goes to stdout, which
# is captured into a shell variable here and written straight into
# ~/.config/vpn-chain/secrets.env — never echoed.
#
# Usage:
#   server/rotate-vless-identity.sh [--reality] [user@vps]
#     --reality   also regenerate the REALITY keypair (updates REALITY_PUBKEY)
#
# After it finishes:  vpn-chain down && vpn-chain up
#
# Requires jq + xray + openssl on the VPS.
set -euo pipefail

ROTATE_REALITY=0
SSH_TARGET=""
for a in "$@"; do
    case "$a" in
        --reality) ROTATE_REALITY=1 ;;
        *) SSH_TARGET="$a" ;;
    esac
done

SECRETS_FILE="${VPN_CHAIN_SECRETS:-$HOME/.config/vpn-chain/secrets.env}"
SSH_KEY="${VPN_CHAIN_SSH_KEY:-$HOME/.ssh/id_1984_relay}"
log() { printf '\033[1;36m[vless-rotate]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[vless-rotate]\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "$SECRETS_FILE" ] || die "secrets file not found: $SECRETS_FILE"
. "$SECRETS_FILE"
SSH_TARGET="${SSH_TARGET:-root@${VPS_IP:-}}"
[ -n "${SSH_TARGET#root@}" ] || die "no VPS_IP in secrets and no target given"

log "Rotating VLESS identity on $SSH_TARGET (reality keypair: $([ "$ROTATE_REALITY" = 1 ] && echo yes || echo no))..."

# Remote: generate + apply new identity, restart xray. Status -> stderr (visible),
# client-side values -> stdout (captured below, never displayed).
NEW="$(ssh -i "$SSH_KEY" -o ConnectTimeout=12 "$SSH_TARGET" "ROTATE_REALITY=$ROTATE_REALITY bash -s" <<'REMOTE'
set -euo pipefail
CFG=/usr/local/etc/xray/config.json
XRAY="$(command -v xray || echo /usr/local/bin/xray)"
say() { printf '[remote] %s\n' "$*" >&2; }

UUID="$("$XRAY" uuid)"
SHORT_ID="$(openssl rand -hex 8)"
PUB=""
PRIV_SET="."
if [ "${ROTATE_REALITY:-0}" = "1" ]; then
    KP="$("$XRAY" x25519)"
    PRIV="$(printf '%s\n' "$KP" | sed -n 's/.*[Pp]rivate[Kk]ey:[[:space:]]*//p' | head -n1)"
    PUB="$(printf '%s\n'  "$KP" | sed -n 's/.*[Pp]ublic[Kk]ey:[[:space:]]*//p'  | head -n1)"
    [ -n "$PRIV" ] || PRIV="$(printf '%s\n' "$KP" | sed -n '1s/.*:[[:space:]]*//p')"
    [ -n "$PUB"  ] || PUB="$(printf '%s\n'  "$KP" | sed -n '2s/.*:[[:space:]]*//p')"
    [ -n "$PRIV" ] && [ -n "$PUB" ] || { say "failed to parse new REALITY keypair"; exit 1; }
fi

say "applying new UUID + short_id$([ "${ROTATE_REALITY:-0}" = 1 ] && echo ' + REALITY keypair')..."
cp "$CFG" "${CFG}.bak.$(date +%s 2>/dev/null || echo rot)"
tmp="$(mktemp --suffix=.json)"   # xray detects format by extension
jq --arg id "$UUID" --arg sid "$SHORT_ID" --arg priv "${PRIV:-}" --argjson rr "${ROTATE_REALITY:-0}" '
    .inbounds[0].settings.clients[0].id = $id
    | .inbounds[0].streamSettings.realitySettings.shortIds = [$sid]
    | (if $rr == 1 then .inbounds[0].streamSettings.realitySettings.privateKey = $priv else . end)
' "$CFG" > "$tmp"
if ! err="$("$XRAY" -test -config "$tmp" 2>&1)"; then
    say "new config failed validation; not applying:"; printf '%s\n' "$err" | tail -5 >&2
    rm -f "$tmp"; exit 1
fi
mv "$tmp" "$CFG"; chmod 644 "$CFG"   # mktemp is 600; service user must read it
systemctl restart xray; sleep 2
systemctl is-active --quiet xray || { say "xray failed to start"; exit 1; }
say "xray restarted with new identity."

# ONLY the client-side values reach stdout (captured locally, never printed).
printf 'UUID=%s\n' "$UUID"
printf 'SHORT_ID=%s\n' "$SHORT_ID"
[ -n "$PUB" ] && printf 'REALITY_PUBKEY=%s\n' "$PUB"
REMOTE
)"

# Parse the captured identity WITHOUT echoing it.
NEW_UUID="$(printf '%s\n' "$NEW" | sed -n 's/^UUID=//p')"
NEW_SHORT_ID="$(printf '%s\n' "$NEW" | sed -n 's/^SHORT_ID=//p')"
NEW_PUB="$(printf '%s\n' "$NEW" | sed -n 's/^REALITY_PUBKEY=//p')"
[ -n "$NEW_UUID" ] && [ -n "$NEW_SHORT_ID" ] \
    || die "server did not return a complete identity; secrets.env left unchanged"

# Rewrite secrets.env in place (values never printed). Only the given key's line
# is touched; everything else is preserved verbatim.
upd() {
    local k="$1" v="$2"
    awk -v k="$k" -v v="$v" '
        $0 ~ "^" k "=" { print k "=" v; found=1; next }
        { print }
        END { if (!found) print k "=" v }
    ' "$SECRETS_FILE" > "${SECRETS_FILE}.tmp" && mv "${SECRETS_FILE}.tmp" "$SECRETS_FILE"
}
cp "$SECRETS_FILE" "${SECRETS_FILE}.bak"
umask 077
upd VLESS_UUID "$NEW_UUID"
upd SHORT_ID   "$NEW_SHORT_ID"
[ -n "$NEW_PUB" ] && upd REALITY_PUBKEY "$NEW_PUB"
chmod 600 "$SECRETS_FILE"

log "Server rotated; $SECRETS_FILE updated (backup: ${SECRETS_FILE}.bak)."
log "Now re-render + reconnect:  vpn-chain down && vpn-chain up"
