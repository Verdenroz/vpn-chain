#!/usr/bin/env bash
# shellcheck disable=SC2016 # jq filters are single-quoted on purpose
# xray-clients.sh — remote half of devices.sh: manage per-device VLESS clients
# on the VPS. Streamed over SSH:  bash -s -- <cmd> [args]
#
#   add NAME | rekey NAME   new UUID + short ID; stdout is ONLY UUID= and SHORT_ID=
#   revoke NAME [--force]   drop the client and its short ID
#   rename OLD NEW | adopt NAME | list
#
# Status goes to stderr. Every mutation runs under one lock: edit a temp copy,
# `xray -test` it, swap it in, restart, and roll back if xray does not come up.
set -euo pipefail

CFG="${CFG:-/usr/local/etc/xray/config.json}"
SIDECAR="${SIDECAR:-/usr/local/etc/xray/devices.json}"
LOCK="${LOCK:-/run/lock/vpn-chain-devices.lock}"
XRAY="${XRAY:-$(command -v xray || echo /usr/local/bin/xray)}"
RELOAD_CMD="${RELOAD_CMD:-systemctl restart xray}"
ACTIVE_CMD="${ACTIVE_CMD:-systemctl is-active --quiet xray}"
STATS_CMD="${STATS_CMD:-}"
SETTLE="${SETTLE:-2}"
TAG="vless-reality-443"
NAME_RE='^[a-z0-9][a-z0-9._-]{0,31}$'

say() { printf '[remote] %s\n' "$*" >&2; }
die() { say "$*"; exit 1; }

SC='{}'

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

check_name() { [[ "$1" =~ $NAME_RE ]] || die "invalid device name: $1"; }

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

has_client() { jq -e --arg tag "$TAG" --arg n "$1" '
    .inbounds[] | select(.tag == $tag) | .settings.clients | any(.[]; .email == $n)' "$CFG" >/dev/null; }

stats_json() {
    local port
    if [ -n "$STATS_CMD" ]; then bash -c "$STATS_CMD"; return; fi
    port="$(jq -er '.inbounds[] | select(.tag == "api") | .port' "$CFG")" || return 1
    "$XRAY" api statsquery --server="127.0.0.1:$port" -pattern 'user>>>' -reset
}

# Counters reset on every xray restart, so fold them in before one.
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
}

write_sidecar() {
    local tmp
    tmp="$(mktemp "$SIDECAR.XXXXXX")"
    jq . <<<"$SC" > "$tmp"
    chmod 600 "$tmp"; mv "$tmp" "$SIDECAR"
}

# commit NEWCFG_JSON: swap in a validated config, restart, roll back on failure.
commit() {
    local tmp bak err
    tmp="$(mktemp --suffix=.json "$(dirname "$CFG")/config.XXXXXX")"
    printf '%s\n' "$1" > "$tmp"
    if ! err="$("$XRAY" -test -config "$tmp" 2>&1)"; then
        say "new config failed validation; not applying:"; printf '%s\n' "$err" | tail -5 >&2
        rm -f "$tmp"; exit 1
    fi
    bak="$CFG.bak.$(date +%s)"
    cp -p "$CFG" "$bak"
    mv "$tmp" "$CFG"; chmod 644 "$CFG"
    if ! { bash -c "$RELOAD_CMD" && sleep "$SETTLE" && bash -c "$ACTIVE_CMD"; }; then
        cp -p "$bak" "$CFG"; bash -c "$RELOAD_CMD" || true
        die "xray did not come up; restored $bak"
    fi
    write_sidecar
}

# edit JQ_FILTER [jq args]: filter applied to the live config, result committed.
edit() {
    local filter="$1"; shift
    commit "$(jq --arg tag "$TAG" "$@" "$filter" "$CFG")"
}

new_identity() {
    UUID="$("$XRAY" uuid)"
    SHORT_ID="$(openssl rand -hex 8)"
}

emit_identity() {
    printf 'UUID=%s\n' "$UUID"
    printf 'SHORT_ID=%s\n' "$SHORT_ID"
}

cmd_add() {
    check_name "$1"
    has_client "$1" && die "device exists: $1"
    jq -e --arg n "$1" 'has($n)' <<<"$SC" >/dev/null && die "device exists in sidecar: $1"
    new_identity
    fold_stats
    SC="$(jq -c --arg n "$1" --arg sid "$SHORT_ID" --arg now "$(now)" \
        '.[$n] = {shortId: $sid, added: $now}' <<<"$SC")"
    edit '(.inbounds[] | select(.tag == $tag)) |= (
            .settings.clients += [{id: $id, flow: "xtls-rprx-vision", email: $n}]
            | .streamSettings.realitySettings.shortIds += [$sid])' \
        --arg id "$UUID" --arg n "$1" --arg sid "$SHORT_ID"
    say "added $1"
    emit_identity
}

cmd_rekey() {
    local old
    has_client "$1" || die "no such device: $1"
    old="$(jq -er --arg n "$1" '.[$n].shortId' <<<"$SC")" || die "no short ID recorded for $1"
    new_identity
    fold_stats
    SC="$(jq -c --arg n "$1" --arg sid "$SHORT_ID" '.[$n].shortId = $sid' <<<"$SC")"
    edit '(.inbounds[] | select(.tag == $tag)) |= (
            (.settings.clients[] | select(.email == $n) | .id) = $id
            | .streamSettings.realitySettings.shortIds |= (map(select(. != $old)) + [$sid]))' \
        --arg id "$UUID" --arg n "$1" --arg sid "$SHORT_ID" --arg old "$old"
    say "rekeyed $1"
    emit_identity
}

cmd_revoke() {
    local name="$1" force="${2:-}" count sid
    if ! has_client "$name" && ! jq -e --arg n "$name" 'has($n)' <<<"$SC" >/dev/null; then
        say "already revoked: $name"; return 0
    fi
    count="$(jq --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .settings.clients | length' "$CFG")"
    [ "$count" -gt 1 ] || [ "$force" = "--force" ] || die "$name is the last device; use --force"
    sid="$(jq -er --arg n "$name" '.[$n].shortId' <<<"$SC")" || die "no short ID recorded for $name"
    fold_stats
    SC="$(jq -c --arg n "$name" 'del(.[$n])' <<<"$SC")"
    # xray rejects an empty shortIds list, so the last revoke leaves an unissued one
    edit '(.inbounds[] | select(.tag == $tag)) |= (
            .settings.clients |= map(select(.email != $n))
            | .streamSettings.realitySettings.shortIds |= (map(select(. != $sid)) | if length == 0 then [$spare] else . end))' \
        --arg n "$name" --arg sid "$sid" --arg spare "$(openssl rand -hex 8)"
    say "revoked $name"
}

cmd_rename() {
    check_name "$2"
    has_client "$1" || die "no such device: $1"
    has_client "$2" && die "device exists: $2"
    fold_stats
    SC="$(jq -c --arg o "$1" --arg n "$2" '.[$n] = .[$o] | del(.[$o])' <<<"$SC")"
    edit '(.inbounds[] | select(.tag == $tag) | .settings.clients[] | select(.email == $o) | .email) = $n' \
        --arg o "$1" --arg n "$2"
    say "renamed $1 to $2"
}

cmd_adopt() {
    local sid
    check_name "$1"
    if jq -e --arg n "$1" 'has($n)' <<<"$SC" >/dev/null && has_client "$1"; then
        say "already adopted: $1"; return 0
    fi
    [ "$SC" = '{}' ] || die "sidecar already has devices; use add"
    [ "$(jq --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .settings.clients | length' "$CFG")" -eq 1 ] \
        || die "more than one client and no sidecar; cannot tell which short ID is whose"
    [ "$(jq --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.shortIds | length' "$CFG")" -eq 1 ] \
        || die "more than one short ID and no sidecar; cannot tell which is the client's"
    sid="$(jq -r --arg tag "$TAG" '.inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.shortIds[0]' "$CFG")"
    SC="$(jq -nc --arg n "$1" --arg sid "$sid" --arg now "$(now)" '{($n): {shortId: $sid, added: $now}}')"
    edit '(.inbounds[] | select(.tag == $tag) | .settings.clients[0].email) = $n' --arg n "$1"
    say "adopted existing client as $1"
}

human() {
    case "$1" in ''|-) echo - ;; *) numfmt --to=iec "$1" ;; esac
}

cmd_list() {
    local n added up down seen
    fold_stats
    [ "$SC" = '{}' ] || write_sidecar
    jq -r --arg tag "$TAG" --argjson sc "$SC" '
        (.inbounds[] | select(.tag == $tag).settings.clients | map(.email)) as $emails
        | ($emails + ($sc | keys) | unique)[] as $n
        | [$n, ($sc[$n].added // "-"), ($sc[$n].up // "-"), ($sc[$n].down // "-"), ($sc[$n].seen // "never")]
        | @tsv' "$CFG" |
    while IFS=$'\t' read -r n added up down seen; do
        printf '%-32s %-20s up %-8s down %-8s seen %s\n' "$n" "$added" "$(human "$up")" "$(human "$down")" "$seen"
    done
    jq -r --arg tag "$TAG" --argjson sc "$SC" '
        (.inbounds[] | select(.tag == $tag)) as $in
        | ($in.settings.clients | map(.email)) as $emails
        | $in.streamSettings.realitySettings.shortIds as $sids
        | ($sc | map(.shortId)) as $known
        | ($emails[] | select(in($sc) | not) | "WARN client without sidecar entry: " + .),
          ($sc | keys[] | select(. as $k | $emails | index($k) == null) | "WARN sidecar entry without client: " + .),
          ($sc | to_entries[] | select(.value.shortId as $s | $sids | index($s) == null) | "WARN short ID missing from config: " + .key),
          ([$sids[] | select(. as $s | $known | index($s) == null)] | select(length > 0) | "WARN \(length) short ID(s) in config not in sidecar")' "$CFG" >&2
}

main() {
    local cmd="${1:-}"
    [ $# -gt 0 ] && shift
    mkdir -p "$(dirname "$LOCK")"
    exec 9>"$LOCK"
    flock -w 30 9 || die "could not take $LOCK"
    check_config
    load
    case "$cmd" in
        add)    [ $# -eq 1 ] || die "usage: add NAME";    cmd_add "$@" ;;
        rekey)  [ $# -eq 1 ] || die "usage: rekey NAME";  cmd_rekey "$@" ;;
        revoke) [ $# -ge 1 ] || die "usage: revoke NAME [--force]"; cmd_revoke "$@" ;;
        rename) [ $# -eq 2 ] || die "usage: rename OLD NEW"; cmd_rename "$@" ;;
        adopt)  [ $# -eq 1 ] || die "usage: adopt NAME";  cmd_adopt "$@" ;;
        list)   cmd_list ;;
        *)      die "usage: $0 add|rekey|revoke|rename|adopt|list" ;;
    esac
}

main "$@"
