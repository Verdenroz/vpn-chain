#!/usr/bin/env bash
# Provision a VLESS+REALITY+Vision relay on a fresh Debian/Ubuntu VPS,
# optionally chaining outbound through ProtonVPN (as a native Xray WireGuard
# outbound, so system routing / SSH are never touched).
#
# Run as root ON THE VPS:  bash deploy-server.sh
# Re-runnable (idempotent-ish): regenerates config from your answers.

set -euo pipefail

# ---- config knobs (env-overridable) --------------------------------------
XRAY_CONFIG="/usr/local/etc/xray/config.json"
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

log() { printf '\033[1;36m[deploy]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root"

log "Installing prerequisites..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl openssl ufw jq >/dev/null

log "Installing Xray-core (official installer)..."
bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install >/dev/null

XRAY_BIN="$(command -v xray || echo /usr/local/bin/xray)"
[ -x "$XRAY_BIN" ] || die "xray not found after install"

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

log "Writing $XRAY_CONFIG ..."
mkdir -p "$(dirname "$XRAY_CONFIG")" /var/log/xray
jq -n \
  --arg uuid "$UUID" --arg dest "$DEST" --arg sni "$SNI" \
  --arg priv "$PRIV" --arg sid "$SHORT_ID" \
  --arg final "$FINAL_OUT" --arg pdns "$EXIT_WG_DNS" --argjson proton "$EXIT_WG_OUT" '
{
  log: { access:"none", error:"/var/log/xray/error.log", loglevel:"warning", maskAddress:"half" },
  inbounds: [ {
    tag:"vless-reality-443", listen:"0.0.0.0", port:443, protocol:"vless",
    settings:{ clients:[ { id:$uuid, flow:"xtls-rprx-vision", email:"client1" } ], decryption:"none" },
    streamSettings:{ network:"tcp", security:"reality",
      realitySettings:{ show:false, dest:$dest, xver:0, serverNames:[$sni], privateKey:$priv, shortIds:[$sid] } },
    sniffing:{ enabled:true, destOverride:["http","tls","quic"], routeOnly:true }
  } ],
  outbounds: ( [ {tag:"direct",protocol:"freedom"}, {tag:"block",protocol:"blackhole"} ]
               + (if $proton==null then [] else [$proton] end) ),
  # Xray has no routing.final field (that is sing-box); its default outbound is
  # the FIRST in the array, so force the exit with an explicit catch-all rule.
  routing:{ domainStrategy:"AsIs",
    rules: ( (if $proton==null then [] else [ { type:"field", ip:[$pdns], outboundTag:"exit-wg" } ] end)
             + [ { type:"field", ip:["geoip:private"], outboundTag:"block" } ]
             + [ { type:"field", network:"tcp,udp", outboundTag:$final } ] ) }
}' > "$XRAY_CONFIG"

"$XRAY_BIN" -test -config "$XRAY_CONFIG" >/dev/null || die "xray config failed validation"

log "Configuring firewall (allow SSH + 443)..."
ufw allow 22/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

log "Enabling and starting xray..."
systemctl enable xray >/dev/null 2>&1 || true
systemctl restart xray
sleep 1
systemctl is-active --quiet xray || die "xray failed to start; check: journalctl -u xray -e"

VPS_IP="$(curl -fsSL https://api.ipify.org || echo YOUR_VPS_IP)"
LINK="vless://${UUID}@${VPS_IP}:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${SNI}&fp=chrome&pbk=${PUB}&sid=${SHORT_ID}&type=tcp&packetEncoding=xudp#vless-reality"

cat <<EOF

=====================================================================
 DEPLOY COMPLETE
=====================================================================
 VPS IP        : ${VPS_IP}
 SNI (dest)    : ${SNI}
 UUID          : ${UUID}
 Public key    : ${PUB}
 Short ID      : ${SHORT_ID}
 Exit path     : $([ "$FINAL_OUT" = proton ] && echo "your machine -> VPS -> ProtonVPN -> internet" || echo "your machine -> VPS (direct exit)")

 CLIENT IMPORT LINK (paste into Throne / sing-box / v2rayN):
 ${LINK}

 Save the public key + short ID + UUID above; you need them for the
 sing-box client config if you build it by hand.
=====================================================================
EOF
