#!/usr/bin/env bash
# Provision a VLESS+REALITY+Vision relay on a fresh Debian/Ubuntu VPS,
# optionally chaining outbound through ProtonVPN (as a native Xray WireGuard
# outbound, so system routing / SSH are never touched).
#
# It also switches the kernel to BBR + fq: the default cubic backs off hard on
# every loss, which cripples throughput over a lossy long-RTT path.
#
# Run as root ON THE VPS from a copy of the whole server/ directory:
#   bash deploy-server.sh
# Re-runnable: rebuilds the config from your answers but keeps the REALITY
# keypair, devices and short IDs. FRESH=1 wipes them and starts over.
#
# The first device's credentials go to /root/vpn-chain-<DEVICE_NAME>.env (0600),
# never to the terminal. Add more with server/devices.sh.

set -euo pipefail

# ---- config knobs (env-overridable) --------------------------------------
XRAY_CONFIG="${XRAY_CONFIG:-/usr/local/etc/xray/config.json}"
CFG="$XRAY_CONFIG"
SETTLE="${SETTLE:-1}"
DEVICE_NAME="${DEVICE_NAME:-device1}"
DEVICE_FILE_DIR="${DEVICE_FILE_DIR:-/root}"
API_PORT="${API_PORT:-10085}"                # loopback stats API (per-device traffic)
FRESH="${FRESH:-0}"
DEPLOY_SKIP_INSTALL="${DEPLOY_SKIP_INSTALL:-0}"   # tests: no apt, xray installer, ufw or systemctl enable
# REALITY needs a "borrowed" TLS site: TLS1.3 + HTTP/2, not a CDN that blocks,
# ideally hosted outside your VPS's own network. Good stable picks below.
SNI="${SNI:-www.samsung.com}"          # <-- the domain you impersonate
DEST="${DEST:-${SNI}:443}"

# ProtonVPN WireGuard params. Leave EXIT_WG_PRIVKEY empty to skip the
# Proton chain (VPS becomes the exit IP directly). Get these from Proton's
# WireGuard config generator (account.protonvpn.com -> Downloads -> WireGuard).
EXIT_WG_PRIVKEY="${EXIT_WG_PRIVKEY:-}"     # [Interface] PrivateKey
EXIT_WG_ADDRESS="${EXIT_WG_ADDRESS:-10.2.0.2/32}"  # [Interface] Address
EXIT_WG_PEER_PUBKEY="${EXIT_WG_PEER_PUBKEY:-}"     # [Peer] PublicKey
EXIT_WG_ENDPOINT="${EXIT_WG_ENDPOINT:-}"           # [Peer] Endpoint  host:port
EXIT_WG_MTU="${EXIT_WG_MTU:-1280}"
EXIT_WG_DNS="${EXIT_WG_DNS:-10.2.0.1}"     # Proton internal resolver (applies NetShield)
# --------------------------------------------------------------------------

# shellcheck source=server/lib/xray-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/xray-common.sh"
# shellcheck source=server/lib/pins.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/pins.sh"
# shellcheck source=server/lib/xray-installer.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/xray-installer.sh"

log() { printf '\033[1;36m[deploy]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root"

if [ "$DEPLOY_SKIP_INSTALL" != 1 ]; then
  log "Installing prerequisites..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq curl openssl ufw jq >/dev/null

  if modprobe tcp_bbr 2>/dev/null; then
    log "Enabling BBR + fq..."
    cat > /etc/sysctl.d/99-vpn-chain-bbr.conf <<EOF
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
net.core.wmem_max=33554432
net.ipv4.tcp_wmem=4096 65536 33554432
EOF
    sysctl -p /etc/sysctl.d/99-vpn-chain-bbr.conf >/dev/null \
      || log "WARNING: BBR sysctls failed to apply; continuing without them."
  else
    log "WARNING: tcp_bbr module unavailable; leaving congestion control unchanged."
  fi

  log "Installing Xray-core $XRAY_VERSION (official installer)..."
  INSTALLER="$(mktemp)"
  fetch_xray_installer "$INSTALLER"
  bash "$INSTALLER" install --version "$XRAY_VERSION" >/dev/null
  rm -f "$INSTALLER"
fi

XRAY_BIN="${XRAY_BIN:-$(command -v xray || echo /usr/local/bin/xray)}"
[ -x "$XRAY_BIN" ] || die "xray not found after install"
XRAY="$XRAY_BIN"
[ "$("$XRAY_BIN" version | sed -n '1s/^Xray \([^ ]*\) .*/v\1/p')" = "$XRAY_VERSION" ] \
  || die "$XRAY_BIN is not Xray $XRAY_VERSION; newer releases break the sing-box REALITY client"

lock
load
fold_stats

# ---- identity: keep what is there unless FRESH=1 --------------------------
KEEP=0
if [ "$FRESH" != 1 ] && [ -f "$XRAY_CONFIG" ]; then
  PROBLEM="$(jq -r --arg tag "$TAG" '
    (.inbounds // [] | map(select(.tag == $tag))[0]) as $in
    | if $in == null then "no \($tag) inbound"
      elif ($in.streamSettings.realitySettings.privateKey // "") == "" then "no REALITY private key"
      elif ($in.streamSettings.realitySettings.shortIds // [] | (length == 0) or any(.[]; . == "")) then "short IDs missing or empty"
      else empty end' "$XRAY_CONFIG" 2>/dev/null)" || PROBLEM="not valid JSON"
  [ -z "$PROBLEM" ] || die "$XRAY_CONFIG cannot be kept ($PROBLEM); fix it, or set FRESH=1 to wipe devices and keys"
  KEEP=1
fi
if [ "$KEEP" = 1 ]; then
  log "Keeping the existing REALITY keypair, devices and short IDs (FRESH=1 to start over)."
  PRIV="$(jq -r --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.privateKey' "$XRAY_CONFIG")"
  CLIENTS="$(jq -c --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .settings.clients' "$XRAY_CONFIG")"
  SHORT_IDS="$(jq -c --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.shortIds' "$XRAY_CONFIG")"
else
  log "Generating REALITY keypair, UUID, and short ID..."
  KEYPAIR="$("$XRAY_BIN" x25519)"
  PRIV="$(printf '%s\n' "$KEYPAIR" | sed -n 's/.*[Pp]rivate[Kk]ey:[[:space:]]*//p' | head -n1)"
  PUB="$(printf '%s\n' "$KEYPAIR"  | sed -n 's/.*[Pp]ublic[Kk]ey:[[:space:]]*//p'  | head -n1)"
  # Newer xray prints "Password"/"PrivateKey" variants; fall back to line parse.
  [ -n "$PRIV" ] || PRIV="$(printf '%s\n' "$KEYPAIR" | sed -n '1s/.*:[[:space:]]*//p')"
  [ -n "$PUB"  ] || PUB="$(printf '%s\n' "$KEYPAIR" | sed -n '2s/.*:[[:space:]]*//p')"
  [ -n "$PRIV" ] && [ -n "$PUB" ] || die "could not parse x25519 keypair:\n$KEYPAIR"

  UUID="$("$XRAY_BIN" uuid)"
  SHORT_ID="$(openssl rand -hex 8)"
  CLIENTS="$(jq -nc --arg id "$UUID" --arg n "$DEVICE_NAME" '[ { id:$id, flow:"xtls-rprx-vision", email:$n } ]')"
  SHORT_IDS="$(jq -nc --arg sid "$SHORT_ID" '[$sid]')"
fi

# ---- build outbound section ----------------------------------------------
if [ -n "$EXIT_WG_PRIVKEY" ]; then
  [ -n "$EXIT_WG_PEER_PUBKEY" ] && [ -n "$EXIT_WG_ENDPOINT" ] \
    || die "Proton chain requested but EXIT_WG_PEER_PUBKEY / EXIT_WG_ENDPOINT missing"
  log "Configuring ProtonVPN WireGuard outbound (chained exit)."
  EXIT_WG_OUT=$(jq -n \
    --arg sk "$EXIT_WG_PRIVKEY" --arg addr "$EXIT_WG_ADDRESS" \
    --arg pk "$EXIT_WG_PEER_PUBKEY" --arg ep "$EXIT_WG_ENDPOINT" \
    --argjson mtu "$EXIT_WG_MTU" '
    { tag:"exit-wg", protocol:"wireguard",
      settings:{ secretKey:$sk, address:[$addr], mtu:$mtu,
        peers:[ { publicKey:$pk, endpoint:$ep, allowedIPs:["0.0.0.0/0","::/0"] } ] } }')
  FINAL_OUT="exit-wg"
else
  log "No Proton key given -> VPS itself is the exit IP."
  EXIT_WG_OUT='null'
  FINAL_OUT="direct"
fi

build_config() {
  jq -n \
    --argjson clients "$CLIENTS" --arg dest "$DEST" --arg sni "$SNI" \
    --arg priv "$PRIV" --argjson sids "$SHORT_IDS" --argjson apiport "$API_PORT" \
    --arg final "$FINAL_OUT" --arg pdns "$EXIT_WG_DNS" --argjson proton "$EXIT_WG_OUT" '
{
  log: { access:"none", error:"/var/log/xray/error.log", loglevel:"warning", maskAddress:"half" },
  stats: {},
  api: { tag:"api", services:["StatsService"] },
  policy: { levels: { "0": { statsUserUplink:true, statsUserDownlink:true } } },
  inbounds: [ {
    tag:"vless-reality-443", listen:"0.0.0.0", port:443, protocol:"vless",
    settings:{ clients:$clients, decryption:"none" },
    streamSettings:{ network:"tcp", security:"reality",
      realitySettings:{ show:false, dest:$dest, xver:0, serverNames:[$sni], privateKey:$priv, shortIds:$sids } },
    sniffing:{ enabled:true, destOverride:["http","tls","quic"], routeOnly:true }
  }, {
    tag:"api", listen:"127.0.0.1", port:$apiport, protocol:"dokodemo-door", settings:{ address:"127.0.0.1" }
  } ],
  outbounds: ( [ (if $proton==null then {tag:"direct",protocol:"freedom",settings:{domainStrategy:"UseIP"}}
                                    else {tag:"direct",protocol:"freedom"} end),
                 {tag:"block",protocol:"blackhole"} ]
               + (if $proton==null then [] else [$proton] end) ),
  # Xray has no routing.final field (that is sing-box); its default outbound is
  # the FIRST in the array, so force the exit with an explicit catch-all rule.
  # A direct exit resolves names before the private-address rule and dials that same
  # answer, so a name for loopback is blocked like its address. A Proton exit dials in its tunnel.
  routing:{ domainStrategy:(if $proton==null then "IPOnDemand" else "AsIs" end),
    rules: ( [ { type:"field", inboundTag:["api"], outboundTag:"api" } ]
             + (if $proton==null then [] else [ { type:"field", ip:[$pdns], outboundTag:"exit-wg" } ] end)
             + [ { type:"field", ip:["geoip:private"], outboundTag:"block" } ]
             + [ { type:"field", network:"tcp,udp", outboundTag:$final } ] ) }
} | if $proton==null then .dns = { servers:["localhost"] } else . end'
}

mkdir -p "$(dirname "$XRAY_CONFIG")" /var/log/xray
if [ "$KEEP" != 1 ]; then
  [ ! -f "$SIDECAR" ] || cp -p "$SIDECAR" "$SIDECAR.bak.$(date +%s)"
  SC="$(jq -nc --arg n "$DEVICE_NAME" --arg sid "$SHORT_ID" --arg now "$(now)" '{($n): {shortId:$sid, added:$now}}')"
fi

if [ "$DEPLOY_SKIP_INSTALL" != 1 ]; then
  log "Configuring firewall (allow SSH + 443)..."
  ufw allow 22/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
  systemctl enable xray >/dev/null 2>&1 || true
fi

log "Writing $XRAY_CONFIG and starting xray..."
swap_config "$(build_config)"

VPS_IP="${VPS_IP:-$(curl -fsSL https://api.ipify.org || echo YOUR_VPS_IP)}"
EXIT_PATH="$([ "$FINAL_OUT" = exit-wg ] && echo "your machine -> VPS -> ProtonVPN -> internet" || echo "your machine -> VPS (direct exit)")"

if [ "$KEEP" = 1 ]; then
  cat <<EOF

=====================================================================
 DEPLOY COMPLETE (existing devices kept)
=====================================================================
 VPS IP        : ${VPS_IP}
 SNI (dest)    : ${SNI}
 Exit path     : ${EXIT_PATH}

 List or add devices with server/devices.sh.
=====================================================================
EOF
  exit 0
fi

DEVICE_FILE="$DEVICE_FILE_DIR/vpn-chain-${DEVICE_NAME}.env"
(
  umask 077
  cat > "$DEVICE_FILE" <<EOF
VPS_IP=${VPS_IP}
REALITY_PUBKEY=${PUB}
SNI=${SNI}
VLESS_UUID=${UUID}
SHORT_ID=${SHORT_ID}
DEVICE_NAME=${DEVICE_NAME}
EOF
)

cat <<EOF

=====================================================================
 DEPLOY COMPLETE
=====================================================================
 VPS IP        : ${VPS_IP}
 SNI (dest)    : ${SNI}
 Device        : ${DEVICE_NAME}
 Exit path     : ${EXIT_PATH}

 Credentials   : ${DEVICE_FILE}  (0600)
 Copy it down as ~/.config/vpn-chain/secrets.env, then delete it here.
 Add more devices with server/devices.sh.
=====================================================================
EOF
