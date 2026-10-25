#!/usr/bin/env bash
# install-desktop.sh: installs the latest released desktop app (Linux x64) as
# the managed copy the app then updates itself from:
#   $XDG_DATA_HOME/vpn-chain/versions/<version>/   unpacked release
#   $XDG_DATA_HOME/vpn-chain/current               symlink to the live version
#   ~/.local/bin/vpn-chain-gui                      launcher
#   $XDG_DATA_HOME/applications/vpn-chain.desktop   menu entry
#
# Touches nothing outside those: sing-box, the kill-switch helper and the
# systemd/polkit setup (cli/setup-desktop.sh) are separate and survive updates.
# Safe to re-run: it installs the latest release, or only refreshes the
# launcher and menu entry when that release is already the live one.
set -euo pipefail

releases="${VPN_CHAIN_RELEASES_URL:-https://github.com/Verdenroz/vpn-chain/releases}"
data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
bin_dir="${VPN_CHAIN_BIN_DIR:-$HOME/.local/bin}"
root="$data_home/vpn-chain"

for tool in curl tar sha256sum; do
  command -v "$tool" >/dev/null || { echo "$tool not found" >&2; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL "$releases/latest/download/manifest.json" -o "$work/manifest.json"
version="$(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' "$work/manifest.json")"
entry="$(sed -n '/"linux-x64"/,/}/p' "$work/manifest.json")"
name="$(sed -n 's/.*"path": *"\([^"]*\)".*/\1/p' <<<"$entry")"
size="$(sed -n 's/.*"size": *\([0-9]*\).*/\1/p' <<<"$entry")"
sha256="$(sed -n 's/.*"sha256": *"\([^"]*\)".*/\1/p' <<<"$entry")"
case "$version" in '' | *[!0-9.]*) echo "unreadable version in the release manifest" >&2; exit 1 ;; esac
[ -n "$name" ] && [ -n "$size" ] && [ -n "$sha256" ] || { echo "the latest release has no linux-x64 build" >&2; exit 1; }

versions="$root/versions"
partial="$versions/.partial-$version"

# Unpacks the verified tarball into versions/$version and points current at it,
# with the same checks as core/update's DesktopInstall.kt; change both together.
install_version() {
  echo "downloading vpn-chain $version"
  curl -fSL "$releases/download/v$version/$name" -o "$work/app.tar.gz"
  [ "$(stat -c %s "$work/app.tar.gz")" = "$size" ] || { echo "download is the wrong size" >&2; exit 1; }
  echo "$sha256  $work/app.tar.gz" | sha256sum -c --status || { echo "download does not match the manifest's SHA-256" >&2; exit 1; }

  listing="$(tar -tzf "$work/app.tar.gz")"
  if grep -Eq '^/|(^|/)\.\.(/|$)' <<<"$listing"; then
    echo "the release has entries outside its folder" >&2; exit 1
  fi
  rm -rf "$partial"
  mkdir -p "$partial"
  trap 'rm -rf "$work" "$partial"' EXIT
  tar -xzf "$work/app.tar.gz" -C "$partial"
  local listing top link dir
  top="$(realpath "$partial")"
  while IFS= read -r -d '' link; do
    case "$(realpath -m "$link" 2>/dev/null)" in
      "$top"/*) ;;
      *) echo "the release links outside its folder: ${link#"$partial"/}" >&2; exit 1 ;;
    esac
  done < <(find "$partial" -type l -print0)
  [ -x "$partial/vpn-chain/bin/vpn-chain" ] || { echo "the release has no vpn-chain/bin/vpn-chain launcher" >&2; exit 1; }

  rm -rf "${versions:?}/$version"
  mv "$partial" "$versions/$version"
  ln -sfn "versions/$version" "$root/.current-tmp"
  mv -T "$root/.current-tmp" "$root/current"

  # Keep the new version and the one it replaces; the app may still be running from that one.
  for dir in "$versions"/*/; do
    dir="${dir%/}"
    [ "${dir##*/}" = "$version" ] || [ "versions/${dir##*/}" = "$previous" ] || rm -rf "$dir"
  done
}

mkdir -p "$versions" "$bin_dir" "$data_home/applications"
previous="$(readlink "$root/current" 2>/dev/null || true)"
# Replacing the live version would delete the files a running app is using.
if [ "$previous" = "versions/$version" ] && [ -x "$versions/$version/vpn-chain/bin/vpn-chain" ]; then
  echo "vpn-chain $version is already installed"
else
  install_version
fi

ln -sfn "$root/current/vpn-chain/bin/vpn-chain" "$bin_dir/vpn-chain-gui"
cat >"$data_home/applications/vpn-chain.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=VPN Chain
GenericName=Multi-hop VPN
Comment=Chain the WireGuard entry, VLESS relay, and exit hops into one tunnel
Exec=$root/current/vpn-chain/bin/vpn-chain
Icon=$root/current/vpn-chain/lib/vpn-chain.png
Terminal=false
Categories=Network;Security;
Keywords=vpn;wireguard;vless;proxy;tunnel;
EOF

echo "vpn-chain $version is installed; run vpn-chain-gui (make sure $bin_dir is on PATH)"
echo "menu entry: $data_home/applications/vpn-chain.desktop (replaces one from installDesktopEntry)"
