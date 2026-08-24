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
#   2. Install the relay systemd unit, so TUN mode doesn't need root and
#      doesn't need a file capability on sing-box either.
#   3. Install the polkit rules so starting the relay and systemd-resolved's
#      per-connect DNS handshake don't prompt for a password every time.
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

# --- 2. the relay service ----------------------------------------------------
# A file capability on sing-box lives on the inode, so every package upgrade
# replaces the binary and silently drops it. The unit grants the capability to
# the process instead, which upgrades can't touch.
SINGBOX_PATH="$(command -v sing-box || true)"
if [ -z "$SINGBOX_PATH" ]; then
    warn "sing-box not found on PATH — install it first, then re-run this script."
elif [ -d /usr/lib/systemd/system ] && command -v systemctl >/dev/null; then
    log "Installing the relay service ($SINGBOX_PATH)..."
    id sing-box >/dev/null 2>&1 || \
        sudo useradd --system --no-create-home --shell /usr/sbin/nologin sing-box
    sudo groupadd -f vpn-chain
    sed "s|^ExecStart=/usr/bin/sing-box|ExecStart=$SINGBOX_PATH|" \
        "$REPO/cli/systemd/vpn-chain-relay.service" \
        | sudo tee /etc/systemd/system/vpn-chain-relay.service >/dev/null
    sudo chmod 644 /etc/systemd/system/vpn-chain-relay.service
    # Owned by the invoking user so the app can write the config without joining
    # a group, which would not reach their running desktop session until relogin.
    sed "s|^d /run/vpn-chain 2770 sing-box|d /run/vpn-chain 2770 $USER|" \
        "$REPO/cli/systemd/vpn-chain-tmpfiles.conf" \
        | sudo tee /etc/tmpfiles.d/vpn-chain.conf >/dev/null
    sudo chmod 644 /etc/tmpfiles.d/vpn-chain.conf
    sudo systemd-tmpfiles --create /etc/tmpfiles.d/vpn-chain.conf
    sudo systemctl daemon-reload
    ok "Relay service installed. sing-box itself needs no capability grant."
    if [ -w /run/vpn-chain ]; then
        ok "Handoff directory ready at /run/vpn-chain. No logout needed."
    else
        warn "/run/vpn-chain is not writable by $USER — the app will fall back to" \
             "starting sing-box itself. Check the owner: ls -ld /run/vpn-chain"
    fi
else
    warn "No systemd here — falling back to a file capability on sing-box."
    sudo setcap cap_net_admin,cap_net_bind_service=+ep "$SINGBOX_PATH"
    warn "Package upgrades replace the binary and drop this grant. Re-run this script if TUN stops working."
fi

# --- 3. polkit rules ---------------------------------------------------------
if [ -d /etc/polkit-1/rules.d ]; then
    log "Installing polkit rules so connect/disconnect doesn't prompt for a password..."
    for POLKIT_SRC in "$REPO"/cli/polkit/*.rules; do
        POLKIT_DEST="/etc/polkit-1/rules.d/$(basename "$POLKIT_SRC")"
        sudo cp "$POLKIT_SRC" "$POLKIT_DEST"
        sudo chown root:polkitd "$POLKIT_DEST" 2>/dev/null || sudo chown root:root "$POLKIT_DEST"
        sudo chmod 640 "$POLKIT_DEST"
        ok "Installed $POLKIT_DEST"
    done
    ok "Takes effect immediately; if prompts persist, sudo systemctl restart polkit."
else
    warn "No /etc/polkit-1/rules.d — this system may not use polkit." \
         "Connect/disconnect and DNS reset may prompt for a password (see cli/polkit/)."
fi

echo
ok "Setup complete. Connecting should now need zero password prompts."
