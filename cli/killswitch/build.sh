#!/usr/bin/env bash
# build.sh — compile the kill-switch helper and print the one-time setcap
# command needed to actually use it. Doesn't run setcap itself (that needs
# root and is a deliberate, explicit step you should run yourself).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$DIR/vpn-chain-killswitch.c"
OUT="$DIR/vpn-chain-killswitch"

c_cyan=$'\033[1;36m'; c_grn=$'\033[1;32m'; c_off=$'\033[0m'
log() { printf '%s[killswitch]%s %s\n' "$c_cyan" "$c_off" "$*"; }

command -v gcc >/dev/null || { echo "gcc not found — install a C compiler first" >&2; exit 1; }
command -v setcap >/dev/null || echo "note: 'setcap' not found — install libcap2-bin (Debian/Ubuntu) or libcap (Arch/Fedora) to grant it below." >&2
[ -f /usr/include/sys/capability.h ] || echo "note: libcap dev headers not found — install libcap-devel/libcap-dev if the compile below fails." >&2

log "Compiling $SRC -> $OUT"
gcc -O2 -Wall -o "$OUT" "$SRC" -lcap

log "Built. One-time step to actually enable it:"
printf '  %ssudo setcap cap_net_admin=eip %s%s\n' "$c_grn" "$OUT" "$c_off"
log "Then either put $OUT on PATH as 'vpn-chain-killswitch', or point the"
log "desktop app at it directly if you customized the binary path."
