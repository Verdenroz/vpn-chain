#!/usr/bin/env bash
# Writes DIST/manifest.json, the index the apps' updater reads, from the built
# release files: release-manifest.sh VERSION DIST
# The version code is read from the built APKs, so it is whatever Android will
# compare; a build whose name differs from VERSION, or APKs that disagree, fail.
set -euo pipefail

version="${1:?usage: release-manifest.sh VERSION DIST}"
dist="${2:?usage: release-manifest.sh VERSION DIST}"

aapt2="${AAPT2:-$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)}"

badge() {
  "$aapt2" dump badging "$dist/vpn-chain-$version-android-$1.apk" | sed -n "s/^package: .* $2='\([^']*\)'.*/\1/p"
}

artifacts='{}'
for entry in android-arm64-v8a:apk android-x86_64:apk linux-x64:tar.gz; do
  key="${entry%%:*}"
  name="vpn-chain-$version-$key.${entry#*:}"
  [ -f "$dist/$name" ] || { echo "missing $name" >&2; exit 1; }
  artifacts=$(jq \
    --arg key "$key" \
    --arg path "$name" \
    --argjson size "$(stat -c %s "$dist/$name")" \
    --arg sha256 "$(sha256sum "$dist/$name" | cut -d' ' -f1)" \
    '. + {($key): {path: $path, size: $size, sha256: $sha256}}' <<<"$artifacts")
done

code=$(badge arm64-v8a versionCode)
[ "$(badge x86_64 versionCode)" = "$code" ] || { echo "APK version codes differ" >&2; exit 1; }
for abi in arm64-v8a x86_64; do
  [ "$(badge "$abi" versionName)" = "$version" ] || { echo "$abi APK is not version $version" >&2; exit 1; }
done

jq -n \
  --arg name "$version" \
  --argjson code "$code" \
  --argjson artifacts "$artifacts" \
  '{version: 1, versionName: $name, versionCode: $code, artifacts: $artifacts}' >"$dist/manifest.json"
