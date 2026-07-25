#!/usr/bin/env bash
# set-exit-direct.sh — switch the VLESS relay to exit from the VPS's OWN IP,
# dropping the ProtonVPN exit hop. Edits the live xray config in place and
# leaves the VLESS/REALITY identity (UUID, keys, short ID, SNI) untouched, so
# existing clients keep working with no profile change.
#
# Run as root ON THE VPS:
#   bash set-exit-direct.sh
#
# Reverting to the Proton exit: restore the backup it prints, or re-run
# deploy-server.sh with the PROTON_* env vars set (note: a full re-run also
# rotates your UUID/keys).
set -euo pipefail

XRAY_CONFIG="${XRAY_CONFIG:-/usr/local/etc/xray/config.json}"
XRAY_BIN="$(command -v xray || echo /usr/local/bin/xray)"

log() { printf '\033[1;36m[set-exit]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (on the VPS)"
[ -f "$XRAY_CONFIG" ] || die "xray config not found: $XRAY_CONFIG"
command -v jq >/dev/null || die "jq not installed — apt-get install -y jq"
[ -x "$XRAY_BIN" ] || die "xray binary not found"

# Already direct? (no proton outbound and catch-all points at direct)
if ! jq -e '.outbounds[]? | select(.tag=="proton")' "$XRAY_CONFIG" >/dev/null 2>&1; then
  log "No Proton outbound present — this relay already exits directly. Nothing to do."
  exit 0
fi

BACKUP="${XRAY_CONFIG}.$(date +%s).bak"
cp -a "$XRAY_CONFIG" "$BACKUP"
log "Backed up current config -> $BACKUP"

# .json suffix matters: xray -test detects config format by extension and
# rejects an extensionless temp file.
tmp="$(mktemp --suffix=.json)"
trap 'rm -f "$tmp"' EXIT
jq '
  # 1. drop the Proton WireGuard outbound
  .outbounds |= map(select(.tag != "proton"))
  # 2. retarget the catch-all (network "tcp,udp") rule to the direct freedom
  #    outbound FIRST — before step 3 removes proton rules, or it would delete
  #    the catch-all too (it still points at proton at this moment).
  | .routing.rules |= map(if (.network == "tcp,udp") then .outboundTag = "direct" else . end)
  # 3. drop any remaining rule that pointed at Proton (e.g. the Proton-DNS rule)
  | .routing.rules |= map(select((.outboundTag // "") != "proton"))
' "$XRAY_CONFIG" > "$tmp" || die "jq transform failed (original left in place)"

"$XRAY_BIN" -test -config "$tmp" >/dev/null \
  || die "new config failed xray validation (original left in place; backup at $BACKUP)"

mv "$tmp" "$XRAY_CONFIG"
trap - EXIT

log "Restarting xray..."
systemctl restart xray
sleep 1
systemctl is-active --quiet xray \
  || die "xray failed to start — revert: cp '$BACKUP' '$XRAY_CONFIG' && systemctl restart xray"

VPS_IP="$(curl -fsSL --max-time 8 https://api.ipify.org || echo '<your-vps-ip>')"
cat <<EOF

=====================================================================
 EXIT SWITCHED TO DIRECT
=====================================================================
 Traffic now exits from the VPS's own IP : ${VPS_IP}
 VLESS identity (UUID / REALITY / SNI)    : unchanged — clients keep working
 Path now : your machine -> VPS -> internet  (no Proton exit hop)

 Revert:  cp '${BACKUP}' '${XRAY_CONFIG}' && systemctl restart xray
=====================================================================
EOF
