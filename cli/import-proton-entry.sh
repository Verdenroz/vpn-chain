#!/usr/bin/env bash
# import-proton-entry.sh — copy a Proton WireGuard .conf into the vpn-chain
# secrets file as the PROTON_ENTRY_* entry-hop keys, so the GUI apps can
# "Import secrets.env". Values move file-to-file: the private key is never
# printed, echoed, or sent anywhere — only a masked summary is shown.
#
# Usage:
#   cli/import-proton-entry.sh [path/to/wireguard.conf]
#
# With no argument it uses the newest *.conf in ~/Downloads.
# Override the target with VPN_CHAIN_SECRETS=/path/to/secrets.env.
set -euo pipefail

SECRETS_FILE="${VPN_CHAIN_SECRETS:-$HOME/.config/vpn-chain/secrets.env}"

c_cyan=$'\033[1;36m'; c_red=$'\033[1;31m'; c_grn=$'\033[1;32m'; c_yel=$'\033[1;33m'; c_off=$'\033[0m'
log()  { printf '%s[import]%s %s\n' "$c_cyan" "$c_off" "$*"; }
die()  { printf '%s[import]%s %s\n' "$c_red"  "$c_off" "$*" >&2; exit 1; }

# --- locate the .conf --------------------------------------------------------
CONF="${1:-}"
if [ -z "$CONF" ]; then
  CONF="$(ls -t "$HOME"/Downloads/*.conf 2>/dev/null | head -n1 || true)"
  [ -n "$CONF" ] || die "no .conf given and none found in ~/Downloads (usage: $0 [file.conf])"
  log "Using newest download: $CONF"
fi
[ -f "$CONF" ] || die "not a file: $CONF"

# --- parse (skips comment lines; grabs the first match) ----------------------
kv() {
  grep -iE "^[[:space:]]*$1[[:space:]]*=" "$CONF" \
    | grep -vE '^[[:space:]]*#' | head -n1 \
    | sed -E "s/^[^=]*=[[:space:]]*//; s/[[:space:]]*\$//"
}

PRIVKEY="$(kv PrivateKey)"
PEER_PUBKEY="$(kv PublicKey)"     # only [Peer] carries PublicKey
RAW_ADDRESS="$(kv Address)"
RAW_ENDPOINT="$(kv Endpoint)"
DNS="$(kv DNS)"

# First IPv4 CIDR from the (possibly comma-separated) Address line.
ADDRESS="$(printf '%s' "$RAW_ADDRESS" | tr ',' '\n' \
  | grep -E '([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]+' | head -n1 | tr -d '[:space:]' || true)"
# First DNS entry, in case the line lists a fallback after the primary.
DNS="$(printf '%s' "$DNS" | tr ',' '\n' | head -n1 | tr -d '[:space:]' || true)"

# Split host:port; strip [] from an IPv6 host.
HOST="${RAW_ENDPOINT%:*}"
PORT="${RAW_ENDPOINT##*:}"
HOST="${HOST#[}"; HOST="${HOST%]}"

# --- validate ----------------------------------------------------------------
missing=""
[ -n "$PRIVKEY" ]     || missing="$missing PrivateKey"
[ -n "$PEER_PUBKEY" ] || missing="$missing [Peer]PublicKey"
[ -n "$ADDRESS" ]     || missing="$missing Address(IPv4)"
[ -n "$HOST" ]        || missing="$missing Endpoint.host"
[ -n "$PORT" ]        || missing="$missing Endpoint.port"
[ -z "$missing" ] || die "could not parse:$missing — is this a Proton WireGuard config?"

# --- write atomically, keys removed/re-added, 0600 ---------------------------
mkdir -p "$(dirname "$SECRETS_FILE")"
umask 077
tmp="$(mktemp "${SECRETS_FILE}.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

# Carry over everything except any prior PROTON_ENTRY_* keys.
if [ -f "$SECRETS_FILE" ]; then
  grep -vE '^(PROTON_ENTRY_PRIVKEY|PROTON_ENTRY_ADDRESS|PROTON_ENTRY_PEER_PUBKEY|PROTON_ENTRY_HOST|PROTON_ENTRY_PORT|PROTON_ENTRY_DNS)=' \
    "$SECRETS_FILE" > "$tmp" || true
fi
{
  printf 'PROTON_ENTRY_PRIVKEY=%s\n'    "$PRIVKEY"
  printf 'PROTON_ENTRY_ADDRESS=%s\n'    "$ADDRESS"
  printf 'PROTON_ENTRY_PEER_PUBKEY=%s\n' "$PEER_PUBKEY"
  printf 'PROTON_ENTRY_HOST=%s\n'       "$HOST"
  printf 'PROTON_ENTRY_PORT=%s\n'       "$PORT"
  if [ -n "$DNS" ]; then printf 'PROTON_ENTRY_DNS=%s\n' "$DNS"; fi
} >> "$tmp"

mv "$tmp" "$SECRETS_FILE"
trap - EXIT
chmod 600 "$SECRETS_FILE"

# --- masked summary (never prints the private key) ---------------------------
log "Wrote entry-hop keys to ${c_grn}$SECRETS_FILE${c_off} (chmod 600)"
log "  PROTON_ENTRY_PRIVKEY     = ${c_grn}[hidden]${c_off}"
log "  PROTON_ENTRY_ADDRESS     = $ADDRESS"
log "  PROTON_ENTRY_PEER_PUBKEY = $PEER_PUBKEY"
log "  PROTON_ENTRY_HOST        = $HOST"
log "  PROTON_ENTRY_PORT        = $PORT"
if [ -n "$DNS" ]; then
  log "  PROTON_ENTRY_DNS         = $DNS (NetShield preserved)"
else
  log "  PROTON_ENTRY_DNS         = ${c_yel}(none — this .conf has no DNS line, NetShield won't apply)${c_off}"
fi
log "Now: in the app, Settings → Import ~/.config/vpn-chain/secrets.env"
