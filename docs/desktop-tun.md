# Desktop TUN mode

TUN is the default: sing-box creates a TUN device and captures all system
traffic, doing both hops itself (`tun` → optional WireGuard entry →
VLESS). The narrower alternative is SOCKS-proxy mode — only apps configured
to use `127.0.0.1:1080` are tunneled, and any entry hop is your own
responsibility to run. Toggle between them at **Settings → Routing → "Route
entire system (TUN)"**.

`SingBoxConfigFactory.renderPlatformTunnelConfig` picks the shape per
platform; desktop's TUN chain mirrors the CLI's
`you → entry hop → VPS → internet` chain, so the GUI and CLI are functionally
equivalent.

## One-time privileged setup

TUN needs `CAP_NET_ADMIN`. Run `cli/setup-desktop.sh` for this, the kill-switch
helper (see `docs/kill-switch.md`), and the polkit rules, in one guided pass.
Safe to re-run.

On systemd it installs `cli/systemd/vpn-chain-relay.service`, which carries the
capability as an `AmbientCapabilities=` grant and runs sing-box as the
unprivileged `sing-box` user. **This is genuinely one-time.** The older approach
of `setcap`-ing the binary is not: a file capability lives on the inode, so every
package upgrade of sing-box replaces the binary and silently drops the grant,
leaving TUN broken until someone re-runs the command. The unit grants the
capability to the process instead, so the binary is never touched.

The setup script adds you to a `vpn-chain` group so the app can hand the rendered
config to the service through `/run/vpn-chain`. **Group membership only takes
effect at login**, and opening a new terminal does not count: the desktop session
that launches the app keeps the groups it started with, so `newgrp` in a shell
does not help an app started from a launcher.

Until you log out and back in, the app treats the unit as unavailable, falls back
to running sing-box itself, and says so in the log. Setup covers that gap by
granting the file capability anyway and telling you it is a stopgap. After the
next login the service takes over and the stale capability stops mattering.

Where there is no systemd, the script falls back to the file capability and says
so:

```
sudo setcap cap_net_admin,cap_net_bind_service=+ep $(command -v sing-box)
```

Connect then fails with `TUNSETIFF: operation not permitted` whenever an upgrade
has cleared it. The app detects the missing capability before connecting and
shows the command as the error. That check is skipped when the unit is installed,
where the binary having no capability is the expected state.

With the service in play, Disconnect has to come from the app or from
`systemctl stop vpn-chain-relay` — `cli/vpn-chain down` signals a pid it does not
own, so it cannot stop a relay running under the unit (it still tears down the
kill switch).

TUN mode also asks `systemd-resolved` to set/revert DNS on the tunnel interface
on every connect and disconnect, which by default means a polkit password prompt
each time. `cli/setup-desktop.sh` installs rules scoped to just that and to
starting the unit (`cli/polkit/`) so it stops prompting. The resolved rule has to
cover the `sing-box` service user as well as your own session: a system service
has no logind session, so it is neither active nor local, and without that branch
DNS setup fails on every connect.

## Adding the WireGuard entry hop

Generate a **dedicated** WireGuard config from your provider (a
*different* one than any other device uses — the same key can't be active in
two places) and import it without exposing the key:

```
cli/import-entry-conf.sh ~/Downloads/your-entry.conf   # → secrets.env
```

then in the app: **Settings → Import ~/.config/vpn-chain/secrets.env**. Or
type the fields into the Settings form's *TUN entry hop* section. Without an
entry the TUN chain is relay-only.

## Conflict with a separate VPN app

If you also run the CLI's local app-based entry hop (a separate VPN client, not the WG
config above), disconnect it before using desktop TUN mode
(e.g. `protonvpn disconnect`) — sing-box now dials the entry peer itself via
the WireGuard entry, so a second VPN connection fights over the routing
table, and its kill switch would block sing-box's traffic. Afterwards confirm
your default route is back on your physical interface
(`ip route show default`) and no `pvpnksintrf0` kill-switch interface
lingers; if it does, you have a permanent kill switch enabled — disable it in
ProtonVPN before connecting.
