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

# shellcheck source=server/lib/xray-common.sh
[ -n "${XRAY_COMMON:-}" ] || . "$(dirname "${BASH_SOURCE[0]}")/lib/xray-common.sh"

NAME_RE='^[a-z0-9][a-z0-9._-]{0,31}$'

check_name() { [[ "$1" =~ $NAME_RE ]] || die "invalid device name: $1"; }

has_client() { jq -e --arg tag "$TAG" --arg n "$1" '
    .inbounds[] | select(.tag == $tag) | .settings.clients | any(.[]; .email == $n)' "$CFG" >/dev/null; }

in_sidecar() { jq -e --arg n "$1" 'has($n)' <<<"$SC" >/dev/null; }

untracked_clients() {
    jq -r --arg tag "$TAG" --argjson sc "$SC" '
        .inbounds[] | select(.tag == $tag) | .settings.clients[].email | select(in($sc) | not)' "$CFG"
}

untracked_sids() {
    jq -r --arg tag "$TAG" --argjson sc "$SC" '
        ($sc | map(.shortId)) as $known
        | .inbounds[] | select(.tag == $tag) | .streamSettings.realitySettings.shortIds[]
        | select(. as $s | $known | index($s) == null)' "$CFG"
}

refuse_untracked() {
    local u
    u="$(untracked_clients | paste -sd' ')"
    [ -z "$u" ] || die "clients not in devices.json ($u); run: devices.sh adopt NAME"
}

# edit JQ_FILTER [jq args]: filter applied to the live config, result swapped in.
edit() {
    local filter="$1"; shift
    swap_config "$(jq --arg tag "$TAG" "$@" "$filter" "$CFG")"
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
    in_sidecar "$1" && die "device exists in sidecar: $1"
    refuse_untracked
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
    refuse_untracked
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
    if ! has_client "$name" && ! in_sidecar "$name"; then
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
    refuse_untracked
    has_client "$2" || in_sidecar "$2" && die "device exists: $2"
    fold_stats
    SC="$(jq -c --arg o "$1" --arg n "$2" '.[$n] = .[$o] | del(.[$o])' <<<"$SC")"
    edit '(.inbounds[] | select(.tag == $tag) | .settings.clients[] | select(.email == $o) | .email) = $n' \
        --arg o "$1" --arg n "$2"
    say "renamed $1 to $2"
}

cmd_adopt() {
    local old sid
    check_name "$1"
    if in_sidecar "$1" && has_client "$1"; then
        say "already adopted: $1"; return 0
    fi
    in_sidecar "$1" && die "device exists in sidecar: $1"
    has_client "$1" && ! untracked_clients | grep -qx "$1" && die "device exists: $1"
    [ "$(untracked_clients | wc -l)" -eq 1 ] || die "adopt needs exactly one client missing from devices.json"
    [ "$(untracked_sids | wc -l)" -eq 1 ] || die "adopt needs exactly one short ID missing from devices.json"
    old="$(untracked_clients)"
    sid="$(untracked_sids)"
    SC="$(jq -c --arg n "$1" --arg sid "$sid" --arg now "$(now)" '.[$n] = {shortId: $sid, added: $now}' <<<"$SC")"
    edit '(.inbounds[] | select(.tag == $tag) | .settings.clients[] | select(.email == $o) | .email) = $n' \
        --arg o "$old" --arg n "$1"
    say "adopted $old as $1"
}

human() {
    case "$1" in ''|-) echo - ;; *) numfmt --to=iec "$1" ;; esac
}

cmd_list() {
    local n added up down seen
    fold_stats
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
    lock
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
