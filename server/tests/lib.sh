#!/usr/bin/env bash
# Shared helpers for the suites. Source it; call finish at the end.

export SERVER=/repo/server
FIX=/repo/server/tests/fixtures
export XRAY=/opt/xray/xray
PASS=0
FAIL=0

# check DESC CMD...: pass when CMD exits 0.
check() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then PASS=$((PASS + 1)); else FAIL=$((FAIL + 1)); printf 'FAIL: %s\n' "$desc"; fi
}

finish() {
    printf '%s: %d passed, %d failed\n' "$(basename "$0")" "$PASS" "$FAIL"
    [ "$FAIL" -eq 0 ]
}

# fresh DIR FIXTURE: a scratch tree with the fixture as the live config.
fresh() {
    W="$(mktemp -d)"
    cp "$FIX/$1" "$W/config.json"
    export CFG="$W/config.json" SIDECAR="$W/devices.json" LOCK="$W/lock"
    export RELOAD_CMD=true ACTIVE_CMD=true SETTLE=0 STATS_CMD=
}

# multi: three devices plus a matching sidecar.
fresh_multi() {
    fresh today.json
    jq '.inbounds[0].settings.clients = [
            {id: "22222222-2222-4222-8222-222222222222", flow: "xtls-rprx-vision", email: "laptop"},
            {id: "33333333-3333-4333-8333-333333333333", flow: "xtls-rprx-vision", email: "phone"},
            {id: "44444444-4444-4444-8444-444444444444", flow: "xtls-rprx-vision", email: "tablet"}]
        | .inbounds[0].streamSettings.realitySettings.shortIds = ["aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "cccccccccccccccc"]' \
        "$FIX/today.json" > "$CFG"
    jq -n '{laptop: {shortId: "aaaaaaaaaaaaaaaa", added: "2026-01-01T00:00:00Z"},
            phone: {shortId: "bbbbbbbbbbbbbbbb", added: "2026-01-02T00:00:00Z"},
            tablet: {shortId: "cccccccccccccccc", added: "2026-01-03T00:00:00Z"}}' > "$SIDECAR"
}

# clients: sorted device emails of the live config.
clients() { jq -r '.inbounds[0].settings.clients[].email' "$CFG" | sort | paste -sd,; }
sids() { jq -r '.inbounds[0].streamSettings.realitySettings.shortIds[]' "$CFG" | sort | paste -sd,; }
uuid_of() { jq -r --arg n "$1" '.inbounds[0].settings.clients[] | select(.email == $n) | .id' "$CFG"; }
valid() { "$XRAY" -test -config "$CFG"; }
