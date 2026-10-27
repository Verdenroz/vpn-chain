<p align="center">
  <img src="assets/branding/logo.svg" width="120" height="120" alt="vpn-chain logo">
</p>

<h1 align="center">vpn-chain</h1>
<p align="center">
  A censorship-resistant VPN you run on your own server, for Android and Linux.
</p>

<p align="center">
  <img src="assets/branding/hops-diagram.svg" width="880" alt="Route: you, through the optional WireGuard entry hop, through the VLESS+REALITY relay VPS, exiting directly to the internet, with a dashed single-hop path from you straight to the relay when the entry hop is off">
</p>

`you → WireGuard entry hop (optional) → your VPS → internet`

Your traffic leaves from your own VPS and looks like ordinary HTTPS on the way
there. With the entry hop on, your network sees only a WireGuard connection to
your VPN provider, and the VPS never sees your real address.

## Setup

You need a Debian or Ubuntu VPS you can SSH into as root, with TCP port 443 open.

1. On your computer, copy the server scripts to the VPS and run the setup.
   Replace `203.0.113.10` with your VPS's address.

   ```
   git clone https://github.com/Verdenroz/vpn-chain && cd vpn-chain
   scp -r server root@203.0.113.10:
   ssh root@203.0.113.10 'DEVICE_NAME=laptop bash server/deploy-server.sh'
   ```

2. Copy this device's connection details down, then delete them from the VPS:

   ```
   mkdir -p -m 700 ~/.config/vpn-chain
   scp root@203.0.113.10:vpn-chain-laptop.env ~/.config/vpn-chain/secrets.env
   ssh root@203.0.113.10 rm vpn-chain-laptop.env
   ```

3. Install the app.
   - **Linux (x64):** install `sing-box` from your package manager, then:

     ```
     cli/install-desktop.sh    # the app, kept up to date, with a menu entry
     cli/setup-desktop.sh      # once: system-wide tunnel, kill switch, no password prompts
     ln -s "$PWD/cli/killswitch/vpn-chain-killswitch" ~/.local/bin/
     ```

   - **Android:** download `vpn-chain-<version>-android-arm64-v8a.apk` from the
     [latest release](https://github.com/Verdenroz/vpn-chain/releases/latest)
     and install it.

4. In the app, open **Settings → Connection → Import** and import
   `~/.config/vpn-chain/secrets.env`. On Android, scan **Show QR** from the
   desktop app instead, or paste the file's contents. Tap **Connect**.

5. Optional entry hop: create a WireGuard config at your VPN provider for this
   device alone, and paste the `.conf` in **Import**, then tap **Use as entry hop**.

To exit through a WireGuard provider instead of the VPS's own address, set the
`EXIT_WG_*` values listed at the top of `server/deploy-server.sh` and run it
again. Running it again keeps your keys and devices.

## Devices

Give each phone or computer its own identity, so you can cut one off without
touching the others. For now you do this from your computer over SSH, with your
usual keys (set `VPN_CHAIN_SSH_KEY` to pick one); it moves into the app later.

```
server/devices.sh add phone      # writes ~/.config/vpn-chain/devices/phone.env
server/devices.sh list           # each device, its traffic, when it was last seen
server/devices.sh revoke phone   # cuts that device off
```

Import `phone.env` on the phone, then delete your copy. **Show QR** shares the
desktop's own identity, so revoking either device would revoke both. For a lost
phone, also delete its WireGuard config at your VPN provider.

## What you can set

| Setting | What it does for you |
|---|---|
| Settings → Profiles | Keeps several connections: your VPS, a plain WireGuard VPN, or both chained |
| Settings → Routing → WireGuard entry hop | Hides your address from the VPS; turn it off without deleting it |
| Settings → Routing → Cloudflare WARP | Sends sites that refuse your VPS through Cloudflare instead (automatic by default) |
| Sites tab, **Use my connection** | Lets a site that refuses both open on your own address, one site at a time |
| Settings → DNS | Blocks malware and scam sites, and optionally ads and trackers |
| Settings → Kill switch | Blocks all traffic while the VPN is down |
| Settings → Routing → Route all apps (Linux) | Off: only apps set to the proxy `127.0.0.1:1080` use the VPN |
| Settings → Automation (Android) | Connects on launch, on boot, and again after a drop |
| Update banner, Settings → About | Installs new releases from inside the app |

Your connection details stay on your devices: in `~/.config/vpn-chain`
(owner-only) on Linux, in app-private storage on Android, never in logs or backups.

## Troubleshooting

- **No internet on Linux after the app stopped:** the kill switch stays up until
  you connect again or press **Disconnect**. `vpn-chain-killswitch down` removes it.
- **Kill switch shows Off on Linux:** the row says why. Usually the helper lost its
  permission after a rebuild: run `cli/setup-desktop.sh` again.
- **Android leaks traffic when the VPN drops:** Android settings → Network &
  internet → VPN → the gear next to vpn-chain → turn on **Always-on VPN** and
  **Block connections without VPN**.
- **Connects but nothing loads:** check that TCP 443 is open on the VPS, then run
  `server/deploy-server.sh` there again. It installs the Xray version the apps are
  tested against; Xray 26.10.21 does not work with them.
- **SSH to the VPS drops during a change:** while you are connected, SSH runs
  through the VPS, and the change restarts it. Reconnect; `server/devices.sh list`
  shows whether it applied.
- **`TUNSETIFF: operation not permitted` on Linux:** a `sing-box` upgrade removed
  its permission. Run `cli/setup-desktop.sh` again.

## Build from source and contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Third-party data

`core/warp/src/jvmShared/resources/vpnchain/public_suffix_list.dat` is the
[Public Suffix List](https://publicsuffix.org/), unmodified, under the
[Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/). Blocked-site
detection uses it to tell a site's registrable domain from a shared host's
tenants. To update it, replace the file with a fresh copy from
https://publicsuffix.org/list/public_suffix_list.dat.
