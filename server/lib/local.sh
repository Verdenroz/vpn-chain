#!/usr/bin/env bash
# shellcheck disable=SC2034 # REMOTE is read by the sourcing script
# Local-side helpers shared by devices.sh and rotate-vless-identity.sh. Sourced.
# The umask keeps every temp and backup file 0600 while secrets are in them.
umask 077

SECRETS_FILE="${VPN_CHAIN_SECRETS:-$HOME/.config/vpn-chain/secrets.env}"
LOG_TAG="${LOG_TAG:-vpn-chain}"
log() { printf '\033[1;36m[%s]\033[0m %s\n' "$LOG_TAG" "$*" >&2; }
die() { printf '\033[1;31m[%s]\033[0m %s\n' "$LOG_TAG" "$*" >&2; exit 1; }

# upd FILE KEY VALUE: rewrite one KEY= line in place, appending it if absent.
upd() {
    local f="$1" k="$2" v="$3"
    awk -v k="$k" -v v="$v" '
        $0 ~ "^" k "=" { print k "=" v; found=1; next }
        { print }
        END { if (!found) print k "=" v }
    ' "$f" > "${f}.tmp" && mv "${f}.tmp" "$f"
}

# resolve_remote [user@vps]: sets REMOTE to the command prefix that runs a shell on the VPS.
resolve_remote() {
    local target="${1:-}"
    if [ -n "${VPN_CHAIN_REMOTE:-}" ]; then
        read -ra REMOTE <<<"$VPN_CHAIN_REMOTE"
        return
    fi
    if [ -f "$SECRETS_FILE" ]; then
        # shellcheck source=/dev/null
        . "$SECRETS_FILE"
    fi
    target="${target:-root@${VPS_IP:-}}"
    [ -n "${target#root@}" ] || die "no VPS_IP in secrets and no target given"
    REMOTE=(ssh -o ConnectTimeout=12)
    [ -z "${VPN_CHAIN_SSH_KEY:-}" ] || REMOTE+=(-i "$VPN_CHAIN_SSH_KEY")
    REMOTE+=("$target")
}
