#!/usr/bin/env bash
# Lints the package with namcap, as root inside an Arch container: lint.sh PKG
# namcap needs the dependencies installed to judge them, so the package goes in first.
set -euo pipefail

pkg="${1:?usage: lint.sh PKG}"

pacman -Syu --noconfirm namcap >/dev/null
pacman -U --noconfirm "$pkg" >/dev/null

# The app bundles its own JRE and a Go binary, runs helpers it does not link against,
# and lives under /opt; every other finding fails.
allowed='elffile-in-questionable-dirs|elffile-unstripped|elffile-nopie|elffile-without-relro|unused-sodepend|dependency-implicitly-satisfied|dependency-detected-not-included java-runtime|dependency-not-needed|library-no-package-associated|external-hooks'

unexpected="$(namcap -m "$pkg" | grep -vE " [EW]: ($allowed)( |$)" || true)"
[ -z "$unexpected" ] || { echo "$unexpected" >&2; exit 1; }
