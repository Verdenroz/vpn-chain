#!/usr/bin/env bash
# Writes DIST/manifest.json, the index the apps' updater reads, from the built
# release files: release-manifest.sh VERSION DIST
set -euo pipefail

version="${1:?usage: release-manifest.sh VERSION DIST}"
dist="${2:?usage: release-manifest.sh VERSION DIST}"

IFS=. read -r major minor patch <<<"$version"
code=$((major * 10000 + minor * 100 + patch))

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

jq -n \
  --arg name "$version" \
  --argjson code "$code" \
  --argjson artifacts "$artifacts" \
  '{version: 1, versionName: $name, versionCode: $code, artifacts: $artifacts}' >"$dist/manifest.json"
