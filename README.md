<p align="center">
  <img src="assets/branding/logo.svg" width="96" height="96" alt="vpn-chain logo">
</p>

<h1 align="center">vpn-chain</h1>

<p align="center">A VPN you run on your own server, for Android and Linux.</p>

<p align="center">
  <a href="https://github.com/Verdenroz/vpn-chain/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://github.com/Verdenroz/vpn-chain/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI runs on main"></a>
  <a href="https://github.com/Verdenroz/vpn-chain/releases/latest"><img src="https://img.shields.io/github/v/release/Verdenroz/vpn-chain" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/platform-Android%20%7C%20Linux-blue" alt="Platforms: Android and Linux">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Verdenroz/vpn-chain" alt="License: GPL-3.0"></a>
</p>

Traffic leaves from your VPS and looks like ordinary HTTPS on the way there.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/branding/route-dark.svg">
  <img src="assets/branding/route-light.svg" width="920" alt="Route: your device, through an optional WireGuard entry hop, to your VPS over VLESS and REALITY, then through an optional WireGuard exit hop to the internet.">
</picture>

With an entry hop, your network sees only a WireGuard connection to your VPN
provider, and the VPS never sees your real address. With an exit hop, sites see your
WireGuard provider's address instead of the VPS's.

## Setup

You need a Debian or Ubuntu VPS with TCP 443 open. If you sign in with an SSH key,
add it first: setup turns off password sign-in. Port 22 stays open.

1. On the VPS, run:

   ```
   curl -fsSL https://github.com/Verdenroz/vpn-chain/releases/latest/download/setup.sh | sudo bash
   ```

   It checks the download, asks for a device name and an optional entry-hop `.conf`
   (Enter skips both), and prints a QR code and a link. They work once, for 24 hours.
   Run the command again for a new one.

2. Install the app from the [latest release](https://github.com/Verdenroz/vpn-chain/releases/latest):

   | Platform | File | Install |
   |---|---|---|
   | Debian, Ubuntu (x64) | `vpn-chain_<version>_amd64.deb` | `sudo apt install ./vpn-chain_<version>_amd64.deb` |
   | Arch (x64) | `vpn-chain-<version>-1-x86_64.pkg.tar.zst` | `sudo pacman -U vpn-chain-<version>-1-x86_64.pkg.tar.zst` |
   | Android | `vpn-chain-<version>-android-arm64-v8a.apk` | Open the file |

   The Linux packages include the tunnel and the kill switch. Later releases install
   from inside the app.

3. Scan the QR code, or paste the link. The app connects and makes this device the
   relay's admin.

4. Optional: for an entry hop, create a WireGuard config at your VPN provider for this
   device alone, paste it in **Settings → Connection → Import**, and tap
   **Use as entry hop**.

Running the setup command again repairs the install and keeps your keys and devices.
It also takes over a relay made by the old `server/deploy-server.sh`; add
`--device laptop` to get a code for a device it already has. If you lose every admin
device, `sudo relayd pair` prints a new first code.

For an exit hop, open
**Settings → Devices → Exit**, tap **Use a WireGuard exit** and paste the provider's
`.conf`. The relay restarts, so every device drops for a few seconds. The same row
switches back.

## Devices

Give each device its own code, so you can cut one off without touching the others.
In **Settings → Devices** on the admin device:

- **Add device** shows a code that works once, for 15 minutes.
- **Cut off** stops a device connecting right away.
- **New code** signs a device out until it scans a fresh code.

On the VPS the same is `sudo relayd pair --new phone`, `sudo relayd device list` and
`sudo relayd device revoke phone`. For a lost phone, also delete its WireGuard config
at your VPN provider.

## Settings

| Setting | What it does |
|---|---|
| Settings → Profiles | Keeps several connections: your VPS, a plain WireGuard VPN, or both chained |
| Settings → Routing → WireGuard entry hop | Hides your address from the VPS |
| Settings → Routing → Cloudflare WARP | Sends sites that refuse your VPS through Cloudflare (automatic by default) |
| Sites tab, **Use my connection** | Opens one site on your own address when nothing else reaches it |
| Settings → DNS | Blocks malware and scam sites, and optionally ads and trackers |
| Settings → Kill switch | Blocks all traffic while the VPN is down |
| Settings → Routing → Route all apps (Linux) | Off: only apps using the proxy `127.0.0.1:1080` go through the VPN |
| Settings → Automation (Android) | Connects on launch, on boot, and after a drop |

Connection details stay on your devices: `~/.config/vpn-chain` (owner-only) on Linux,
app-private storage on Android. They never go in logs or backups.

## Troubleshooting

- **No internet on Linux after the app stopped.** The kill switch stays up until you
  connect or press **Disconnect**. `vpn-chain-killswitch down` removes it.
- **Kill switch shows Off, or `TUNSETIFF: operation not permitted`, on Linux.**
  Reinstall the package from a terminal in your own desktop session:
  `sudo apt install --reinstall ./vpn-chain_<version>_amd64.deb`, or `sudo pacman -U`
  the package on Arch.
- **Android leaks traffic when the VPN drops.** In Android's VPN settings, open the
  gear next to vpn-chain and turn on **Always-on VPN** and
  **Block connections without VPN**.
- **Connects but nothing loads.** Check that TCP 443 is open on the VPS, then run the
  setup command again. It installs the Xray version the apps are tested with.
- **SSH to the VPS drops during a change.** While you are connected, SSH goes through
  the VPS, and the change restarts it. Reconnect; `sudo relayd device list` shows
  whether it applied.

## Contributing

Building from source and contributing: [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0-or-later. The bundled sing-box is GPL-3.

`core/warp/src/jvmShared/resources/vpnchain/public_suffix_list.dat` is the
[Public Suffix List](https://publicsuffix.org/), unmodified, under the
[Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/). Update it from
https://publicsuffix.org/list/public_suffix_list.dat.

`core/geoip/src/commonMain/composeResources/files/geoip_ranges.bin` and
`geoip_cities.tsv` are built from the
[DB-IP Lite city database](https://db-ip.com/db/download/ip-to-city-lite) under
[Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
IP Geolocation by [DB-IP](https://db-ip.com). Rebuild with
`core/geoip/tools/build_geoip.py`.
