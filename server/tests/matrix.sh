#!/usr/bin/env bash
# Run e2e.sh for every Xray version in XRAY_VERSIONS (default: the pin) against
# every sing-box in SINGBOX_VERSIONS (default: libbox's and desktop's) and print
# the pair table. KNOWN_BAD=1 adds Xray v26.10.21 x sing-box 1.14.3 and requires
# it to fail on the sing-box reach check alone.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=server/lib/pins.sh
. "$HERE/../lib/pins.sh"
XRAY_VERSIONS="${XRAY_VERSIONS:-$XRAY_VERSION}"
export SINGBOX_VERSIONS="${SINGBOX_VERSIONS:-$SINGBOX_VERSIONS_DEFAULT}"
BAD_XRAY=v26.10.21 BAD_SINGBOX=1.14.3
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

rc=0
for x in $XRAY_VERSIONS; do
    E2E_XRAY_VERSION="$x" "$HERE/e2e.sh" | tee -a "$OUT" || rc=1
    REFUSE_XRAY_VERSION="$x" "$HERE/refuse.sh" || rc=1
done

if [ "${KNOWN_BAD:-0}" = 1 ]; then
    bad="$(E2E_XRAY_VERSION=$BAD_XRAY SINGBOX_VERSIONS=$BAD_SINGBOX "$HERE/e2e.sh" || true)"
    printf '%s\n' "$bad"
    others="$(grep '^FAIL: ' <<<"$bad" | grep -v "^FAIL: sing-box $BAD_SINGBOX reaches the target" || true)"
    if ! grep -qx "PAIR xray=$BAD_XRAY sing-box=$BAD_SINGBOX FAIL" <<<"$bad"; then
        echo "known-bad pair xray $BAD_XRAY x sing-box $BAD_SINGBOX did NOT fail" >&2
        rc=1
    elif [ -n "$others" ]; then
        echo "known-bad pair failed on more than the sing-box reach check" >&2
        rc=1
    else
        echo "PAIR xray=$BAD_XRAY sing-box=$BAD_SINGBOX expected FAIL (confirmed)" >> "$OUT"
    fi
fi

printf '\n%-12s %-10s %s\n' xray sing-box result
sed -n 's/^PAIR xray=\([^ ]*\) sing-box=\([^ ]*\) \(.*\)$/\1 \2 \3/p' "$OUT" \
    | while read -r x s r; do printf '%-12s %-10s %s\n' "$x" "$s" "$r"; done
exit "$rc"
