#!/usr/bin/env bash
# diagnose-tun.sh — capture vpn-chain TUN diagnostics to a file.
# Run WHILE the desktop app's TUN mode is connected (even if slow); it saves to
# a file, then you disconnect and paste the file. Local probes are instant; the
# network probes have hard timeouts so the script always finishes.
#
#   cli/diagnose-tun.sh            # writes /tmp/vpn-chain-tun-diag.txt
#   cli/diagnose-tun.sh out.txt    # custom path
#
# No secrets are read or printed (relay.log is sing-box output, not the config).

OUT="${1:-/tmp/vpn-chain-tun-diag.txt}"
RL="${XDG_RUNTIME_DIR:-/tmp}/vpn-chain/relay.log"

exec > >(tee "$OUT") 2>&1
sec() { printf '\n===== %s =====\n' "$*"; }
strip() { sed 's/\x1b\[[0-9;]*m//g'; }

sec "sing-box process"
pgrep -af "sing-box run" | grep -v pgrep || echo "(sing-box NOT running)"

sec "TUN interface + MTU  (want: mtu 1400 on the 10.19.19.1 device)"
echo "--- interfaces still on 172.19.0.1 are Docker bridges, NOT the tun ---"
ip -o addr show 2>/dev/null | grep '172\.19\.0\.1' || echo "(none)"
TUNDEV="$(ip -o addr show 2>/dev/null | awk '/10\.19\.19\.1/{print $2; exit}')"
if [ -n "${TUNDEV:-}" ]; then
  ip -o link show "$TUNDEV" | sed -E 's/\\.*//'
  echo "tun device: $TUNDEV"
else
  echo "(no interface has 10.19.19.1 — TUN not up)"
fi

sec "routing"
ip route show default
echo "--- route to a public IP (should go via the tun) ---"
ip route get 1.1.1.1 2>&1 | head -2

sec "sing-box outbound: VLESS to VPS (tcp:443)"
ss -tnp 2>/dev/null | grep -i sing-box | grep ':443' | head

sec "TCP details on the VPS connection (look for high 'retrans')"
ss -tinp 2>/dev/null | grep -A1 ':443' | grep -iE 'retrans|rtt:|cwnd|mss' | head

sec "TCP retransmit rate during a 2 MB fetch (high ratio = MTU / TCP-in-TCP)"
nstat -n 2>/dev/null   # baseline
curl --max-time 40 -s -o /dev/null \
  -w 'fetch: %{size_download}B in %{time_total}s = %{speed_download}B/s\n' \
  'https://speed.cloudflare.com/__down?bytes=2000000' || echo "(fetch failed/timed out)"
echo "--- TCP counters delta ---"
nstat 2>/dev/null | grep -iE 'TcpRetransSegs|TcpOutSegs|TcpInSegs|TcpLostRetransmit' || echo "(nstat unavailable)"

sec "DNS + connect + TTFB timing through the chain"
curl --max-time 30 -s -o /dev/null \
  -w 'dns=%{time_namelookup}s connect=%{time_connect}s tls=%{time_appconnect}s ttfb=%{time_starttransfer}s total=%{time_total}s\n' \
  https://www.cloudflare.com || echo "(curl failed/timed out)"
echo "--- exit IP (should be the VPS's IP, not your ISP) ---"
curl --max-time 20 -s https://api.ipify.org; echo

sec "latency: ping the exit path"
ping -c 4 -W 3 1.1.1.1 2>&1 | tail -3 || echo "(ping blocked/failed)"

sec "relay errors (from relay.log)"
grep -iE "error|fatal|timeout" "$RL" 2>/dev/null | strip | tail -20 \
  || echo "(none — good, or log empty)"

sec "relay.log tail (last 50 lines)"
tail -50 "$RL" 2>/dev/null | strip || echo "(no relay.log)"

sec "done"
echo "Saved to $OUT — disconnect TUN, then paste this file's contents."
