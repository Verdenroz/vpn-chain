#!/usr/bin/env bash
# shellcheck disable=SC2016 # bash -c bodies expand in the child
# xray-clients.sh against fixtures, with the real xray doing the -test.
# shellcheck source=server/tests/lib.sh
. "$(dirname "$0")/lib.sh"

run() {
    OUT="$(bash "$SERVER/xray-clients.sh" "$@" 2>"$W/err")"
    RC=$?
    ERR="$(cat "$W/err")"
}

# ---- add -----------------------------------------------------------------
fresh_multi
run add watch
check "add exits 0" test "$RC" -eq 0
check "add stdout is two lines" test "$(printf '%s\n' "$OUT" | wc -l)" -eq 2
check "add stdout is only UUID= and SHORT_ID=" test -z "$(printf '%s\n' "$OUT" | sed -E '/^UUID=[0-9a-f-]{36}$/d;/^SHORT_ID=[0-9a-f]{16}$/d')"
NEW_UUID="$(printf '%s\n' "$OUT" | sed -n 's/^UUID=//p')"
NEW_SID="$(printf '%s\n' "$OUT" | sed -n 's/^SHORT_ID=//p')"
check "stderr leaks no identity" bash -c '! printf "%s" "$1" | grep -qE "$2|$3"' _ "$ERR" "$NEW_UUID" "$NEW_SID"
check "client added keyed by email" test "$(clients)" = "laptop,phone,tablet,watch"
check "client carries the new uuid" test "$(uuid_of watch)" = "$NEW_UUID"
check "short ID added, others kept" test "$(sids)" = "$(printf '%s\n' aaaaaaaaaaaaaaaa bbbbbbbbbbbbbbbb cccccccccccccccc "$NEW_SID" | sort | paste -sd,)"
check "sidecar records the short ID" test "$(jq -r .watch.shortId "$SIDECAR")" = "$NEW_SID"
check "sidecar is 0600" test "$(stat -c %a "$SIDECAR")" = 600
check "config is 0644" test "$(stat -c %a "$CFG")" = 644
check "config still passes xray -test" valid
check "backup written" bash -c 'ls "$1".bak.* >/dev/null' _ "$CFG"
check "no temp file left behind" bash -c '! ls "$1" | grep -qE "^config\.[A-Za-z0-9]{6}\.json$"' _ "$W"

run add watch
check "duplicate name refused" test "$RC" -ne 0
for bad in Bad -x "a b" "$(printf 'a%.0s' $(seq 33))" ""; do
    run add "$bad"
    check "name '$bad' refused" test "$RC" -ne 0
done

# ---- revoke --------------------------------------------------------------
fresh_multi
run revoke phone
check "revoke exits 0" test "$RC" -eq 0
check "phone client gone" test "$(clients)" = "laptop,tablet"
check "phone short ID gone" test "$(sids)" = "aaaaaaaaaaaaaaaa,cccccccccccccccc"
check "phone sidecar entry gone" test "$(jq -r 'keys | join(",")' "$SIDECAR")" = "laptop,tablet"
check "config passes xray -test after revoke" valid
run revoke phone
check "second revoke is a no-op" test "$RC" -eq 0
check "second revoke says already revoked" bash -c 'grep -q "already revoked" <<<"$1"' _ "$ERR"

fresh_multi
run revoke laptop; run revoke phone
run revoke tablet
check "last device refused without --force" test "$RC" -ne 0
check "last device kept" test "$(clients)" = "tablet"
run revoke tablet --force
check "last device removed with --force" test "$RC" -eq 0
check "forced revoke leaves exactly one unissued short ID" test "$(jq '.inbounds[0].streamSettings.realitySettings.shortIds | length' "$CFG")" -eq 1
check "no empty short ID after forced revoke" bash -c '! jq -e ".inbounds[0].streamSettings.realitySettings.shortIds | index(\"\")" "$1"' _ "$CFG"

# ---- rename / rekey ------------------------------------------------------
fresh_multi
run rename phone pixel
check "rename exits 0" test "$RC" -eq 0
check "email renamed" test "$(clients)" = "laptop,pixel,tablet"
check "rename keeps the uuid" test "$(uuid_of pixel)" = 33333333-3333-4333-8333-333333333333
check "sidecar rekeyed by name" test "$(jq -r 'keys | join(",")' "$SIDECAR")" = "laptop,pixel,tablet"
check "sidecar keeps the short ID" test "$(jq -r .pixel.shortId "$SIDECAR")" = bbbbbbbbbbbbbbbb
run rename laptop tablet
check "rename onto an existing name refused" test "$RC" -ne 0

fresh_multi
run rekey phone
check "rekey exits 0" test "$RC" -eq 0
check "rekey prints two identity lines" test "$(printf '%s\n' "$OUT" | wc -l)" -eq 2
check "rekey changed the uuid" test "$(uuid_of phone)" != 33333333-3333-4333-8333-333333333333
check "rekey dropped the old short ID" bash -c '! grep -q bbbbbbbbbbbbbbbb "$1"' _ "$CFG"
check "rekey left other devices alone" test "$(uuid_of laptop)" = 22222222-2222-4222-8222-222222222222
check "rekey keeps three short IDs" test "$(jq '.inbounds[0].streamSettings.realitySettings.shortIds | length' "$CFG")" -eq 3
check "rekey updates the sidecar" test "$(jq -r .phone.shortId "$SIDECAR")" = "$(printf '%s\n' "$OUT" | sed -n 's/^SHORT_ID=//p')"

# ---- adopt ---------------------------------------------------------------
fresh today.json
cp "$CFG" "$W/before"
run adopt laptop
check "adopt exits 0" test "$RC" -eq 0
check "adopt changes only the email" test "$(jq -c '.inbounds[0].settings.clients[0].email = "client1"' "$CFG")" = "$(jq -c . "$W/before")"
check "adopt renamed the client" test "$(clients)" = laptop
check "adopt recorded the existing short ID" test "$(jq -r .laptop.shortId "$SIDECAR")" = 0123456789abcdef
check "adopt keeps the uuid" test "$(uuid_of laptop)" = 11111111-1111-4111-8111-111111111111
cp "$CFG" "$W/after"
run adopt laptop
check "second adopt is a no-op" test "$RC" -eq 0
check "second adopt leaves the config alone" cmp "$CFG" "$W/after"
run adopt other
check "adopt refused once devices exist" test "$RC" -ne 0

fresh today.json
jq '.inbounds[0].streamSettings.realitySettings.shortIds += ["dddddddddddddddd"]' "$FIX/today.json" > "$CFG"
cp "$CFG" "$W/before"
run adopt laptop
check "adopt refused with two short IDs and no sidecar" test "$RC" -ne 0
check "refused adopt leaves the config alone" cmp "$CFG" "$W/before"
fresh_multi
rm "$SIDECAR"
run adopt laptop
check "adopt refused with several clients and no sidecar" test "$RC" -ne 0

# ---- refusals ------------------------------------------------------------
fresh today.json
jq '.inbounds[0].streamSettings.realitySettings.shortIds += [""]' "$FIX/today.json" > "$CFG"
cp "$CFG" "$W/before"
run add watch
check "config with an empty short ID refused" test "$RC" -ne 0
check "empty-short-ID config untouched" cmp "$CFG" "$W/before"

fresh today.json
printf '{ "inbounds": [\n' > "$CFG"
cp "$CFG" "$W/before"
run add watch
check "corrupt config refused" test "$RC" -ne 0
check "corrupt config untouched" cmp "$CFG" "$W/before"

# ---- failure paths -------------------------------------------------------
fresh_multi
cat > "$W/xray-wrap" <<'WRAP'
#!/usr/bin/env bash
[ "$1" = "-test" ] && exit 1
exec /opt/xray/xray "$@"
WRAP
chmod +x "$W/xray-wrap"
cp "$CFG" "$W/before"; cp "$SIDECAR" "$W/sc-before"
XRAY="$W/xray-wrap" run add watch
check "failed -test exits non-zero" test "$RC" -ne 0
check "failed -test prints no identity" test -z "$OUT"
check "failed -test leaves config byte-identical" cmp "$CFG" "$W/before"
check "failed -test leaves sidecar byte-identical" cmp "$SIDECAR" "$W/sc-before"
check "failed -test leaves no temp file" bash -c '! ls "$1" | grep -qE "^config\.[A-Za-z0-9]{6}\.json$"' _ "$W"

ACTIVE_CMD=false run add watch
check "failed restart exits non-zero" test "$RC" -ne 0
check "failed restart prints no identity" test -z "$OUT"
check "rollback restores the config byte-for-byte" cmp "$CFG" "$W/before"
check "rollback leaves the sidecar untouched" cmp "$SIDECAR" "$W/sc-before"

# ---- list ----------------------------------------------------------------
fresh_multi
cat > "$W/stats.json" <<'JSON'
{"stat":[
 {"name":"user>>>phone>>>traffic>>>uplink","value":"2048"},
 {"name":"user>>>phone>>>traffic>>>downlink","value":"1048576"},
 {"name":"user>>>laptop>>>traffic>>>uplink"}]}
JSON
STATS_CMD="cat $W/stats.json" run list
check "list exits 0" test "$RC" -eq 0
check "list shows phone traffic" bash -c 'grep -E "^phone .*up 2.0K +down 1.0M" <<<"$1"' _ "$OUT"
check "list shows an idle device as never seen" bash -c 'grep -E "^laptop .*seen never" <<<"$1"' _ "$OUT"
check "list prints no uuid or short ID" bash -c '! grep -qE "[0-9a-f]{16}|[0-9a-f]{8}-" <<<"$1"' _ "$OUT"
STATS_CMD="cat $W/stats.json" run list
check "counters accumulate across lists" bash -c 'grep -E "^phone .*up 4.0K" <<<"$1"' _ "$OUT"
check "phone has a last-seen time" bash -c '[ "$(jq -r .phone.seen "$1")" != null ]' _ "$SIDECAR"

fresh_multi
jq 'del(.phone)' "$SIDECAR" > "$W/sc" && mv "$W/sc" "$SIDECAR"
jq '.inbounds[0].streamSettings.realitySettings.shortIds += ["dddddddddddddddd"]' "$CFG" > "$W/c" && mv "$W/c" "$CFG"
run list
check "list flags a client without a sidecar entry" bash -c 'grep -q "client without sidecar entry: phone" <<<"$1"' _ "$ERR"
check "list flags an orphan short ID" bash -c 'grep -q "short ID(s) in config not in sidecar" <<<"$1"' _ "$ERR"

# ---- locking -------------------------------------------------------------
fresh_multi
bash "$SERVER/xray-clients.sh" add one >/dev/null 2>&1 &
bash "$SERVER/xray-clients.sh" add two >/dev/null 2>&1 &
wait
check "concurrent adds both land" test "$(clients)" = "laptop,one,phone,tablet,two"
check "concurrent adds leave a valid config" valid
check "concurrent adds keep sidecar in step" test "$(jq -r 'keys | join(",")' "$SIDECAR")" = "laptop,one,phone,tablet,two"

finish
