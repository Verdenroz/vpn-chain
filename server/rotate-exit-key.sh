#!/usr/bin/env bash
# rotate-exit-key.sh — replace the server's ProtonVPN WireGuard credentials
# (the chained exit hop) with a freshly generated Proton config, WITHOUT
# regenerating the VLESS/REALITY identity (clients keep working).
#
# Rotate whenever the WireGuard private key may be exposed. The new key travels
# from your downloaded Proton .conf straight to the VPS over your SSH session;
# it is never printed, committed, or sent anywhere else.
#
# 1. account.protonvpn.com -> Downloads -> WireGuard: create a NEW config
#    (this mints a new keypair). DELETE the old config there to revoke the
#    leaked key.
# 2. Run:  ./rotate-exit-key.sh <new-proton.conf> [user@vps]
#
# Requires: jq on the VPS. Reads a standard wg-quick .conf locally.
set -euo pipefail

CONF="${1:-}"
# Default SSH target derives from the runtime secrets (VPS_IP), so no infra
# details are baked into the repo. Override by passing user@host as $2.
SECRETS_FILE="${VPN_CHAIN_SECRETS:-$HOME/.config/vpn-chain/secrets.env}"
[ -f "$SECRETS_FILE" ] && . "$SECRETS_FILE"
SSH_TARGET="${2:-root@${VPS_IP:-}}"
SSH_KEY="${VPN_CHAIN_SSH_KEY:-$HOME/.ssh/id_1984_relay}"
XRAY_CONFIG="${XRAY_CONFIG:-/usr/local/etc/xray/config.json}"

die() { printf '\033[1;31m[rotate]\033[0m %s\n' "$*" >&2; exit 1; }
log() { printf '\033[1;36m[rotate]\033[0m %s\n' "$*"; }

[ -n "$CONF" ] && [ -f "$CONF" ] || die "usage: $0 <new-proton.conf> [user@vps]"

# Parse the wg-quick .conf locally (values stay on this machine until pushed via SSH).
ini_get() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$CONF" | head -n1 | tr -d '\r'; }
PRIV="$(ini_get PrivateKey)"
ADDR="$(ini_get Address | cut -d, -f1)"
PEER_PUB="$(ini_get PublicKey)"
ENDPOINT="$(ini_get Endpoint)"
[ -n "$PRIV" ] && [ -n "$ADDR" ] && [ -n "$PEER_PUB" ] && [ -n "$ENDPOINT" ] \
    || die "could not parse PrivateKey/Address/PublicKey/Endpoint from $CONF"
EP_HOST="${ENDPOINT%%:*}"; EP_PORT="${ENDPOINT##*:}"
log "Parsed new Proton config (endpoint $EP_HOST:$EP_PORT, address $ADDR). Key stays hidden."

SSH=(ssh -i "$SSH_KEY" -o ConnectTimeout=12 "$SSH_TARGET")

# Patch the proton outbound on the server. Values arrive via stdin env, not argv,
# so the private key never appears in the remote process list.
log "Updating $XRAY_CONFIG on $SSH_TARGET and restarting xray..."
PRIV="$PRIV" ADDR="$ADDR" PEER_PUB="$PEER_PUB" EP_HOST="$EP_HOST" EP_PORT="$EP_PORT" \
"${SSH[@]}" "PRIV='$PRIV' ADDR='$ADDR' PEER_PUB='$PEER_PUB' EP_HOST='$EP_HOST' EP_PORT='$EP_PORT' bash -s" <<'REMOTE'
set -euo pipefail
CFG=/usr/local/etc/xray/config.json
cp "$CFG" "${CFG}.bak.$(date +%s 2>/dev/null || echo rotate)"
# xray detects config format from the file extension, so the temp file must end in .json.
tmp="$(mktemp --suffix=.json)"
jq --arg sk "$PRIV" --arg addr "$ADDR" --arg pk "$PEER_PUB" --arg ep "${EP_HOST}:${EP_PORT}" '
  (.outbounds[] | select(.tag=="exit-wg") | .settings) |=
    (.secretKey = $sk
     | .address = [$addr]
     | .peers[0].publicKey = $pk
     | .peers[0].endpoint = $ep)
' "$CFG" > "$tmp"
if ! err="$(xray -test -config "$tmp" 2>&1)"; then
    echo "new config failed validation; not applying:"; echo "$err" | tail -5
    rm -f "$tmp"; exit 1
fi
mv "$tmp" "$CFG"
chmod 644 "$CFG"   # mktemp makes 600; xray's non-root service user must be able to read it
systemctl restart xray
sleep 2
systemctl is-active --quiet xray || { echo "xray failed to start"; exit 1; }
echo "xray restarted with rotated Proton key."
REMOTE

log "Done. Verify the exit IP changed:"
log "  curl --socks5-hostname 127.0.0.1:1080 https://api.ipify.org   (with the relay up)"
