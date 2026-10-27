#!/usr/bin/env bash
# Boots a Debian container with systemd as PID 1, installs relay-jobs, relay-privileged, the
# units and an xray with a REALITY inbound, then drives jobs through it with a sing-box client:
#   a) a job outlives the client that submitted it
#   b) with no confirm, a bad xray and a bad config are rolled back and the client works again
#   c) with a confirm, the new xray version stays
#   d) the privileged runner refuses unknown verbs and a traversing job id
# xray 26.3.27 is the good version; 26.10.21 is rejected by the sing-box client under test.
# The container is never privileged: only SYS_ADMIN, which systemd needs for its cgroup.
set -eEuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY="$(cd "$HERE/../../../.." && pwd)"
GOOD=26.3.27
BAD=26.10.21
SINGBOX=1.14.3
NAME="relay-jobs-live-$$"
WORK="$(mktemp -d)"

cleanup() {
    docker kill "$NAME" >/dev/null 2>&1 || true
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker rmi -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
dump() {
    echo "FAILED at line $1" >&2
    docker logs "$NAME" >&2 || true
    docker exec "$NAME" journalctl --no-pager -n 60 -u relay-privileged -u xray -u client >&2 || true
}
trap cleanup EXIT
trap 'dump $LINENO' ERR

g() { docker exec "$NAME" "$@"; }
as_relayd() { docker exec -u relayd "$NAME" "$@"; }
step() { printf '\n== %s\n' "$*"; }
no() {
    if "$@"; then
        echo "unexpectedly succeeded: $*" >&2
        return 1
    fi
}
eq() {
    if [ "$1" != "$2" ]; then
        echo "expected '$2', got '$1'" >&2
        return 1
    fi
}
pass() { printf 'PASS: %s\n' "$*"; }

state() { as_relayd relay-jobs status "$1" | cut -d' ' -f1; }

wait_state() { # id state seconds
    local i
    for ((i = 0; i < ${3:-60} * 2; i++)); do
        [ "$(state "$1")" = "$2" ] && return 0
        sleep 0.5
    done
    echo "job $1 is $(as_relayd relay-jobs status "$1"), not $2" >&2
    return 1
}

wait_xray() {
    local i
    for ((i = 0; i < 40; i++)); do
        g bash -c 'exec 3<>/dev/tcp/127.0.0.1/4443' 2>/dev/null && return 0
        sleep 0.25
    done
    echo "xray never listened" >&2
    return 1
}

tunnel_works() {
    local i
    wait_xray
    for ((i = 0; i < 10; i++)); do
        g through-tunnel && return 0
        sleep 1
    done
    return 1
}

tunnel_refused() {
    wait_xray
    no g through-tunnel
}

# The version xray last logged on starting; /proc/PID/exe is unreadable without CAP_SYS_PTRACE.
running_version() {
    g bash -c "journalctl -u xray -b -o cat --no-pager | sed -n 's/.*Xray \\([0-9.]*\\) started.*/\\1/p' | tail -1"
}

current_version() { g readlink /opt/vpn-chain/xray/current; }
xray_pid() { g systemctl show -p MainPID --value xray; }
config_hash() { g sha256sum /usr/local/etc/xray/config.json | cut -d' ' -f1; }

step "build, fetch pinned binaries, boot the container"
(cd "$RELAY" && cargo build -p relay-jobs --locked)
BUILD="${CARGO_TARGET_DIR:-$RELAY/target}/debug"
BINS="$("$RELAY/crates/live-harness/fetch-bins.sh")"
BAD_XRAY="$("$RELAY/../server/tests/fetch-xray.sh" "v$BAD")"
mkdir -p "$WORK/bins/xray/$GOOD" "$WORK/bins/xray/$BAD" "$WORK/bins/sing-box"
cp "$BINS/xray-v$GOOD/xray" "$WORK/bins/xray/$GOOD/xray"
cp "$BAD_XRAY" "$WORK/bins/xray/$BAD/xray"
cp "$BINS/sing-box-$SINGBOX/sing-box" "$WORK/bins/sing-box/sing-box"

# CI sets --security-opt apparmor=unconfined: Ubuntu's docker profile denies the cgroup remount.
read -ra EXTRA <<<"${RELAY_JOBS_DOCKER_EXTRA:-}"
docker build -q -t "$NAME" "$HERE" >/dev/null
docker run -d --name "$NAME" "${EXTRA[@]}" \
    --cap-add SYS_ADMIN --cgroupns=private --tmpfs /run --tmpfs /run/lock --tmpfs /tmp \
    -v "$HERE:/opt/live:ro" -v "$WORK/bins:/opt/bins:ro" \
    -v "$BUILD:/opt/build:ro" -v "$RELAY/packaging/systemd:/opt/units:ro" \
    "$NAME" bash -c 'mount -o remount,rw /sys/fs/cgroup && exec /sbin/init' >/dev/null
for _ in $(seq 60); do
    case "$(g systemctl is-system-running 2>/dev/null || true)" in running | degraded) break ;; esac
    sleep 1
done
g bash /opt/live/provision.sh
g systemd-analyze verify /etc/systemd/system/relay-privileged.path /etc/systemd/system/relay-privileged.service
pass "systemd-analyze verify accepts the path and service units"

step "baseline: sing-box reaches the target through xray $GOOD"
tunnel_works
eq "$(running_version)" "$GOOD"
BASE_HASH="$(config_hash)"
pass "sing-box $SINGBOX connects through xray $GOOD"

step "a) a job outlives the client that submitted it"
before="$(xray_pid)"
docker exec -u relayd "$NAME" bash -c 'relay-jobs submit restart-xray >/tmp/a.id; exec sleep 600' &
client=$!
for _ in $(seq 40); do g test -s /tmp/a.id && break; sleep 0.25; done
kill -9 "$client"
g pkill -u relayd -x sleep || true
wait "$client" 2>/dev/null || true
no g pgrep -u relayd
id="$(g cat /tmp/a.id)"
wait_state "$id" awaiting-confirm 30
[ "$(xray_pid)" != "$before" ]
as_relayd relay-jobs confirm "$id"
wait_state "$id" confirmed 30
pass "the restart ran and was confirmed with the submitting session gone"

step "c) with a confirm, the new version stays"
id="$(as_relayd relay-jobs submit swap-xray "$BAD")"
wait_state "$id" awaiting-confirm 30
eq "$(running_version)" "$BAD"
tunnel_refused
as_relayd relay-jobs confirm "$id"
wait_state "$id" confirmed 30
sleep 1
eq "$(current_version)" "$BAD"
eq "$(running_version)" "$BAD"
[ -z "$(g ls /var/lib/vpn-chain/snapshots)" ]
pass "xray $BAD stays after confirm (sing-box rejects it, as the matrix records)"
id="$(as_relayd relay-jobs submit swap-xray "$GOOD")"
wait_state "$id" awaiting-confirm 30
tunnel_works
as_relayd relay-jobs confirm "$id"
wait_state "$id" confirmed 30
pass "swapping back to $GOOD and confirming after the client reconnected"

step "b) with no confirm, the version and the config roll back"
id="$(as_relayd relay-jobs submit swap-xray "$BAD")"
wait_state "$id" awaiting-confirm 30
tunnel_refused
wait_state "$id" rolled-back 40
eq "$(current_version)" "$GOOD"
eq "$(running_version)" "$GOOD"
tunnel_works
pass "unconfirmed swap to $BAD rolled back to $GOOD and sing-box connects again"

g bash -c "sed 's/dest.test/other.test/' /usr/local/etc/xray/config.json >/var/lib/vpn-chain/staged/config.json \
    && chown relayd:relayd /var/lib/vpn-chain/staged/config.json && chmod 600 /var/lib/vpn-chain/staged/config.json"
id="$(as_relayd relay-jobs submit apply-config)"
wait_state "$id" awaiting-confirm 30
[ "$(config_hash)" != "$BASE_HASH" ]
tunnel_refused
wait_state "$id" rolled-back 40
eq "$(config_hash)" "$BASE_HASH"
eq "$(g stat -c '%U:%G %a' /usr/local/etc/xray/config.json)" "root:xray 640"
tunnel_works
pass "unconfirmed config rolled back byte for byte, owner and mode kept"

step "job files are private and hold no key"
eq "$(g bash -c 'stat -c %a /var/lib/vpn-chain/jobs/*.json | sort -u')" 600
key="$(g sed -n 's/.*"privateKey": "\([^"]*\)".*/\1/p' /usr/local/etc/xray/config.json)"
no g grep -rqF "$key" /var/lib/vpn-chain/jobs /var/lib/vpn-chain/requests /var/lib/vpn-chain/snapshots
pass "jobs are 0600 and carry no private key"

step "d) the runner refuses what is not a fixed verb"
g systemctl stop relay-privileged.path
pid="$(xray_pid)"
g bash -c 'cd /var/lib/vpn-chain/requests
    printf "rm-rf %s\n" 0123456789abcdef0123456789abcdef >a.req
    printf "restart-xray ../../etc/passwd\n" >b.req
    printf "swap-xray 0123456789abcdef0123456789abcdef ../../tmp/x\n" >c.req
    printf "swap-xray 0123456789abcdef0123456789abcdef 99.0.0\n" >d.req
    ln -s /etc/shadow e.req'
if out="$(g env -i /usr/local/libexec/vpn-chain/relay-privileged drain 2>&1)"; then
    echo "the runner accepted bad requests" >&2
    exit 1
fi
eq "$(grep -c refused <<<"$out")" 5
[ -z "$(g ls -A /var/lib/vpn-chain/requests)" ]
eq "$(xray_pid)" "$pid"
eq "$(current_version)" "$GOOD"
rc=0
g /usr/local/libexec/vpn-chain/relay-privileged restart-xray 2>/dev/null || rc=$?
eq "$rc" 64
pass "unknown verb, traversing id, bad version and a symlink were refused; xray untouched"
