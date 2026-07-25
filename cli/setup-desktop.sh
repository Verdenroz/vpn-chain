#!/usr/bin/env bash
# setup-desktop.sh — one-time privileged setup for a fresh desktop device:
# everything TUN mode, the kill switch, and password-free connect/disconnect
# need, in one guided run instead of hunting down three separate steps.
#
# Deliberately explicit, not auto-elevating: this is a script you choose to
# run, and every privileged command in it is right here to read before you
# do. Nothing here runs unless you invoke this script yourself.
#
# Steps:
#   1. Build the kill-switch helper (cli/killswitch/build.sh) and setcap it.
#   2. setcap sing-box itself, so TUN mode doesn't need root.
#   3. Install the polkit rule so systemd-resolved's per-connect DNS
#      handshake doesn't prompt for a password every connect/disconnect.
#
# Safe to re-run — every step is idempotent.
set -euo pipefail

SELF="$(readlink -f "$0")"
REPO="$(cd "$(dirname "$SELF")/.." && pwd)"

c_cyan=$'\033[1;36m'; c_yel=$'\033[1;33m'; c_red=$'\033[1;31m'; c_grn=$'\033[1;32m'; c_off=$'\033[0m'
log()  { printf '%s[setup]%s %s\n' "$c_cyan" "$c_off" "$*"; }
warn() { printf '%s[setup]%s %s\n' "$c_yel"  "$c_off" "$*" >&2; }
ok()   { printf '%s[setup]%s %s\n' "$c_grn"  "$c_off" "$*"; }

command -v sudo >/dev/null || { echo "sudo not found — run the commands in this script as root manually." >&2; exit 1; }

# --- 1. kill-switch helper --------------------------------------------------
log "Building the kill-switch helper..."
"$REPO/cli/killswitch/build.sh"

KILLSWITCH_BIN="$REPO/cli/killswitch/vpn-chain-killswitch"
log "Granting CAP_NET_ADMIN to $KILLSWITCH_BIN..."
sudo setcap cap_net_admin=eip "$KILLSWITCH_BIN"
ok "Kill-switch helper ready. Put it on PATH: cli/vpn-chain install (or symlink it yourself)."

# --- 2. sing-box itself ------------------------------------------------------
if SINGBOX_PATH="$(command -v sing-box)"; then
    log "Granting CAP_NET_ADMIN + CAP_NET_BIND_SERVICE to $SINGBOX_PATH..."
    sudo setcap cap_net_admin,cap_net_bind_service=+ep "$SINGBOX_PATH"
    ok "sing-box ready for TUN mode without root."
else
    warn "sing-box not found on PATH — install it first, then re-run this script."
fi

# --- 3. polkit rule for systemd-resolved ------------------------------------
POLKIT_SRC="$REPO/cli/polkit/10-vpn-chain-resolve1.rules"
POLKIT_DEST="/etc/polkit-1/rules.d/10-vpn-chain-resolve1.rules"
if [ -d /etc/polkit-1/rules.d ]; then
    log "Installing polkit rule so DNS reset doesn't prompt for a password..."
    sudo cp "$POLKIT_SRC" "$POLKIT_DEST"
    sudo chown root:polkitd "$POLKIT_DEST"
    sudo chmod 640 "$POLKIT_DEST"
    ok "Polkit rule installed. Takes effect immediately; if prompts persist, sudo systemctl restart polkit."
else
    warn "No /etc/polkit-1/rules.d — this system may not use polkit/systemd-resolved." \
         "You'll likely see no DNS-reset password prompts to begin with; if you do, install manually (see cli/polkit/10-vpn-chain-resolve1.rules)."
fi

echo
ok "Setup complete. Connecting should now need zero password prompts."
