<p align="center">
  <img src="assets/branding/logo.svg" width="120" height="120" alt="vpn-chain logo">
</p>

<h1 align="center">vpn-chain</h1>
<p align="center">
  A layered, censorship-resistant VPN chain for Android and Linux desktop.
</p>

<p align="center">
  <img src="assets/branding/hops-diagram.svg" width="880" alt="Route: you, through the optional WireGuard entry hop, through the VLESS+REALITY relay VPS, exiting directly to the internet, with a dashed single-hop path from you straight to the relay when the entry hop is off">
</p>

Traffic goes `you → WireGuard entry hop → VLESS+REALITY relay VPS → internet`.
A network observer sees WireGuard to your entry provider, never a connection to
your VPS. The entry hop is optional: without it the VPS sees your address, and
the traffic still looks like HTTPS to your SNI.

## Quick start

You need a relay VPS first: see [Set up a connection](#set-up-a-connection).

### Linux desktop (x64)

```
git clone https://github.com/Verdenroz/vpn-chain && cd vpn-chain
cli/install-desktop.sh     # latest release, self-updating, adds a menu entry
cli/setup-desktop.sh       # one time: TUN capability, kill switch, polkit rules
vpn-chain-gui
```

The desktop app drives the `sing-box` binary, so install `sing-box` first.
Details: [docs/desktop-tun.md](docs/desktop-tun.md).

### Android

1. Download `vpn-chain-<version>-android-arm64-v8a.apk` from the
   [latest release](https://github.com/Verdenroz/vpn-chain/releases/latest)
   (`x86_64` for an emulator).
2. Allow installs from your browser or file manager, and install it.
3. Open the app and import a connection, then tap **Connect**.

Turn on Always-on VPN as the kill switch:
[docs/android.md](docs/android.md#kill-switch).

### Linux CLI

The CLI exposes a local SOCKS/HTTP proxy at `127.0.0.1:1080`. It needs
`sing-box`, `envsubst` and the ProtonVPN CLI (it uses ProtonVPN as the entry
hop).

```
cli/vpn-chain install    # symlink into ~/.local/bin
vpn-chain up             # entry hop + relay
vpn-chain status         # kill switch helper, entry path, exit IP
vpn-chain down           # stop the relay (add --disconnect-entry to drop the entry app too)
```

## Set up a connection

1. Deploy the relay. On a fresh Debian or Ubuntu VPS, as root, run
   `server/deploy-server.sh` from a copy of `server/`. It installs Xray with
   VLESS+REALITY, switches the kernel to BBR and `fq` when the module is
   available (the default cubic backs off hard on a lossy long path), and
   writes the first device's values to `/root/vpn-chain-<name>.env` (`0600`),
   never to the terminal. Re-running keeps the keys and devices.
2. Put them in `secrets.env`, outside the repo:

   ```
   mkdir -p ~/.config/vpn-chain
   cp examples/secrets.env.example ~/.config/vpn-chain/secrets.env
   chmod 600 ~/.config/vpn-chain/secrets.env
   ```

3. Optional entry hop: generate a WireGuard config from your provider, a
   different one for each device, and import it without printing the key:
   `cli/import-entry-conf.sh ~/Downloads/entry.conf`.
4. In the app, open **Settings → Connection → Import**. Paste or import
   `secrets.env`, or paste a WireGuard `.conf`. The app shows a review (the
   server and entry it would use) before anything is saved. **Use it** switches
   to the connection; **Save** keeps it for later.
5. On Android you can scan a QR code from the desktop app instead
   (**Connection → Show QR**).

A pasted WireGuard `.conf` becomes a WireGuard-only connection: a plain tunnel
to that peer, with no relay. **Use as entry hop** attaches it to the active
connection instead.

Real credentials never live in the repo. The CLI renders an ephemeral config
from `secrets.env` at run time. The apps keep the profile in DataStore and never
log credentials. On desktop, `~/.config/vpn-chain` is owner-only (`0700`, files
`0600`, repaired at start), each rendered config is a private temp file, and the
app refuses a runtime directory it does not own. Android keeps the profile in
app-private storage and excludes it from backups and device transfer.

**One identity per device.** The QR code carries this device's own live
credentials. To give a phone an identity you can revoke without touching the
others, create it on the server with `server/devices.sh add`. See
[docs/devices.md](docs/devices.md).

## What it does

| Feature | Where | Doc |
|---|---|---|
| Saved connections: relay, WireGuard-only, or both chained | Settings → Profiles | [architecture](docs/architecture.md) |
| Optional entry hop, switchable off without editing the profile | Settings → Routing | [android](docs/android.md), [desktop-tun](docs/desktop-tun.md) |
| Cloudflare WARP exit, automatic for sites that refuse the VPS | Settings → Routing, Sites | [warp-exit](docs/warp-exit.md) |
| Use my connection: a tap lets a refused site leave on your own address | Chain prompt, Sites | [warp-exit](docs/warp-exit.md#failing-open) |
| DNS filtering (threats, or threats + ads) | Settings → DNS | [dns-filtering](docs/dns-filtering.md) |
| Kill switch: nftables on desktop, Always-on VPN on Android | Settings → Kill switch | [kill-switch](docs/kill-switch.md) |
| In-app updates | Update banner, Settings → About | [Updating](#updating) |

## Updating

The Android app and the managed Linux install check the latest release at start
and every 24 hours. A banner offers **Update x.y.z**; **Settings → About →
Check now** looks again.

- **Android** installs through the system installer. The first update asks you
  to allow installs from the app. Keep the app open until the install prompt
  shows. Debug builds are never offered updates.
- **Linux desktop** updates the copy `cli/install-desktop.sh` installs under
  `~/.local/share/vpn-chain`. Builds from source and the `.deb` are never
  offered updates. The relay keeps running through an update.
- The CLI and server scripts update with `git pull`.

Release process and signing: [docs/releasing.md](docs/releasing.md).

## Documentation

| Doc | Covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Modules, layering, how the two platforms differ |
| [docs/android.md](docs/android.md) | Android tunnel, kill switch, staying connected |
| [docs/desktop-tun.md](docs/desktop-tun.md) | Desktop setup: TUN, the relay service, the entry hop |
| [docs/kill-switch.md](docs/kill-switch.md) | The nftables helper and its design |
| [docs/dns-filtering.md](docs/dns-filtering.md) | Blocklists and how they are built |
| [docs/warp-exit.md](docs/warp-exit.md) | WARP tail, automatic detection, failing open |
| [docs/devices.md](docs/devices.md) | Per-device identities on the relay |
| [docs/rotate-vless-identity.md](docs/rotate-vless-identity.md) | Rotate the relay credentials |
| [docs/rotate-exit-key.md](docs/rotate-exit-key.md) | Rotate the server's upstream WireGuard key |
| [docs/releasing.md](docs/releasing.md) | Tag, sign and publish a release |

## Build from source

```
./gradlew :desktopApp:run                  # desktop app
./gradlew :androidApp:assembleDebug        # Android debug APK
./gradlew :desktopApp:installDesktopEntry  # launcher entry for a source build
```

Gradle 8.13 must run on JDK 17 to 21. If your default `java` is newer, set
`org.gradle.java.home=/usr/lib/jvm/java-21-openjdk` in `gradle.properties`, or in
`~/.gradle/gradle.properties` to keep it machine-local. Versions live in
`gradle/libs.versions.toml`.

## Third-party data

`core/warp/src/jvmShared/resources/vpnchain/public_suffix_list.dat` is the
[Public Suffix List](https://publicsuffix.org/), unmodified, under the
[Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/). Blocked-site
detection uses it to tell a site's registrable domain from a shared host's
tenants. To update it, replace the file with a fresh copy from
https://publicsuffix.org/list/public_suffix_list.dat.
