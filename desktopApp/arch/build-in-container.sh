#!/usr/bin/env bash
# Runs build-arch.sh in an Arch container: build-in-container.sh APP_IMAGE OUT_DIR
# The container is not privileged, and the build runs in it as an unprivileged user.
set -euo pipefail

APP_IMAGE="$(realpath "${1:?usage: build-in-container.sh APP_IMAGE OUT_DIR}")"
out="$(realpath -m "${2:?usage: build-in-container.sh APP_IMAGE OUT_DIR}")"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

mkdir -p "$out"
docker run --rm -v "$REPO:/repo:ro" -v "$APP_IMAGE:/app-image:ro" -v "$out:/out" "$(cat "$HERE/image")" bash -euc '
pacman -Syu --noconfirm --needed base-devel libcap curl >/dev/null
useradd --create-home builder
# The kill-switch helper builds into its source directory.
cp -a /repo /build
chown -R builder /build /out
runuser -u builder -- /build/desktopApp/arch/build-arch.sh /app-image /out
'
