#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2034 # jq filters are single-quoted; XRAY_COMMON is read by sourcers
# Server-side helpers shared by xray-clients.sh, deploy-server.sh and the remote
# half of rotate-vless-identity.sh. Sourced, or streamed ahead of the script.
# One lock covers every edit: edit a temp copy, `xray -test` it, swap it in,
# restart, and roll back if xray does not come up.
set -euo pipefail
XRAY_COMMON=1

CFG="${CFG:-/usr/local/etc/xray/config.json}"
SIDECAR="${SIDECAR:-$(dirname "$CFG")/devices.json}"
LOCK="${LOCK:-/run/lock/vpn-chain-devices.lock}"
XRAY="${XRAY:-$(command -v xray || echo /usr/local/bin/xray)}"
RELOAD_CMD="${RELOAD_CMD:-systemctl restart xray}"
ACTIVE_CMD="${ACTIVE_CMD:-systemctl is-active --quiet xray}"
STATS_CMD="${STATS_CMD:-}"
SETTLE="${SETTLE:-2}"
TAG="vless-reality-443"

say() { printf '[remote] %s\n' "$*" >&2; }
die() { say "$*"; exit 1; }

SC='{}'

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

lock() {
    mkdir -p "$(dirname "$LOCK")"
    exec 9>"$LOCK"
    flock -w 30 9 || die "could not take $LOCK"
}

check_config() {
    jq -e . "$CFG" >/dev/null 2>&1 || die "$CFG is not valid JSON"
    jq -e --arg tag "$TAG" '
        .inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.shortIds
        | type == "array"' "$CFG" >/dev/null 2>&1 || die "no $TAG inbound with shortIds"
    jq -e --arg tag "$TAG" '
        .inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.shortIds
        | all(.[]; . != "")' "$CFG" >/dev/null || die "empty short ID in config; refusing to edit"
}

load() {
    [ -f "$SIDECAR" ] && SC="$(jq -c . "$SIDECAR")" || SC='{}'
}

write_sidecar() {
    local tmp
    [ "$SC" != '{}' ] || [ -f "$SIDECAR" ] || return 0
    tmp="$(mktemp "$SIDECAR.XXXXXX")"
    jq . <<<"$SC" > "$tmp"
    chmod 600 "$tmp"; mv "$tmp" "$SIDECAR"
}

stats_json() {
    local port
    if [ -n "$STATS_CMD" ]; then bash -c "$STATS_CMD"; return; fi
    port="$(jq -er '.inbounds[] | select(.tag == "api") | .port' "$CFG")" || return 1
    "$XRAY" api statsquery --server="127.0.0.1:$port" -pattern 'user>>>' -reset
}

# The read resets xray's counters, so the folded totals are written out at once.
fold_stats() {
    local st
    st="$(stats_json 2>/dev/null)" || return 0
    SC="$(jq -c --argjson st "$st" --arg now "$(now)" '
        def total($n; $dir): [($st.stat // [])[]
            | select(.name == "user>>>" + $n + ">>>traffic>>>" + $dir) | (.value // 0 | tonumber)] | add // 0;
        with_entries(.key as $n | .value |= (
            total($n; "uplink") as $u | total($n; "downlink") as $d
            | .up = ((.up // 0) + $u) | .down = ((.down // 0) + $d)
            | if $u + $d > 0 then .seen = $now else . end))' <<<"$SC")"
    write_sidecar
}

# swap_config NEWCFG_JSON: validate, swap in, restart; on failure restore the
# old config. The sidecar in $SC is written only once xray is up.
swap_config() {
    local tmp bak="" err
    tmp="$(mktemp --suffix=.json "$(dirname "$CFG")/config.XXXXXX")"
    printf '%s\n' "$1" > "$tmp"
    if ! err="$("$XRAY" -test -config "$tmp" 2>&1)"; then
        say "new config failed validation; not applying:"; printf '%s\n' "$err" | tail -5 >&2
        rm -f "$tmp"; exit 1
    fi
    if [ -f "$CFG" ]; then bak="$CFG.bak.$(date +%s)"; cp -p "$CFG" "$bak"; fi
    mv "$tmp" "$CFG"; chmod 644 "$CFG"
    if ! { bash -c "$RELOAD_CMD" 9>&- && sleep "$SETTLE" && bash -c "$ACTIVE_CMD"; }; then
        if [ -n "$bak" ]; then cp -p "$bak" "$CFG"; else rm -f "$CFG"; fi
        bash -c "$RELOAD_CMD" 9>&- || true
        die "xray did not come up; restored ${bak:-no config}"
    fi
    write_sidecar
}
