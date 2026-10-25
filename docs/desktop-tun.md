# Desktop setup and TUN mode

The desktop app drives the `sing-box` binary. In TUN mode (the default) sing-box
creates a TUN device, captures all system traffic and does both hops itself:
`tun → optional WireGuard entry → VLESS relay`. The CLI's chain has the same
shape, so the GUI and CLI are functionally equivalent.

## Set up

1. Install `sing-box` (it must be on `PATH`).
2. Run the one-time setup. It is safe to re-run.

   ```
   cli/setup-desktop.sh
   ```

   It does three things, each with `sudo`:

   | Step | Result |
   |---|---|
   | Build and `setcap` the kill-switch helper | `cli/killswitch/vpn-chain-killswitch`, see [kill-switch.md](kill-switch.md) |
   | Install the relay unit | `vpn-chain-relay.service` and the `/run/vpn-chain` handoff directory |
   | Install polkit rules | No password prompt on connect, disconnect and DNS setup |

3. Put the kill-switch helper on `PATH`; the app looks it up by name:

   ```
   ln -s "$PWD/cli/killswitch/vpn-chain-killswitch" ~/.local/bin/
   ```

4. Import a connection (**Settings → Chain identity → Import**) and connect.

To switch to a local SOCKS proxy instead (only apps set to `127.0.0.1:1080` are
tunneled, and any entry hop is yours to run), turn off **Settings → Routing →
Route entire system**. The proxy listens on loopback only and has no login, so on
a shared machine any local user can send traffic through the chain. Use TUN mode
on multi-user machines.

## The relay service

The unit gives sing-box `CAP_NET_ADMIN` as an `AmbientCapabilities=` grant and
runs it as the unprivileged `sing-box` user.

| Path | Role |
|---|---|
| `/etc/systemd/system/vpn-chain-relay.service` | The unit, from `cli/systemd/vpn-chain-relay.service` |
| `/etc/tmpfiles.d/vpn-chain.conf` | Creates `/run/vpn-chain` (mode `2770`, owned by you, group `vpn-chain`) |
| `/run/vpn-chain/relay.json` | Config the app writes; the unit reads it through the group |
| `/run/vpn-chain/relay.log` | The unit's log, which the Logs screen follows |
| `/etc/polkit-1/rules.d/10-vpn-chain-*.rules` | Start/stop of the unit and `systemd-resolved` DNS calls |

Owning the directory means the app writes the config with no group membership
and no logout. If it is not writable, the app falls back to running sing-box
itself and says so in the log.

**No systemd.** The script falls back to a file capability:

```
sudo setcap cap_net_admin,cap_net_bind_service=+ep $(command -v sing-box)
```

A sing-box package upgrade replaces the binary and drops the capability, and
connect then fails with `TUNSETIFF: operation not permitted`. The app checks for
the capability before connecting and shows that command as the error. Re-run the
setup script. The check is skipped when the unit is installed, since the binary
having no capability is expected there.

**Stopping.** With the unit in play, `cli/vpn-chain down` stops the unit too. If
`systemctl stop` is refused (over SSH, say, where the polkit rule does not
apply), it exits without touching anything else, so the relay keeps running with
its kill switch in place.

**Polkit.** TUN mode asks `systemd-resolved` to set and revert DNS on every
connect and disconnect. The installed rules cover just that and starting the
unit. The resolved rule covers the `sing-box` user too: a system service has no
logind session, so without that branch DNS setup fails on every connect.

## Entry hop

The entry hop is optional. Without one the TUN chain is relay-only, and the VPS
sees your address.

1. Generate a WireGuard config at your provider for this device alone; a key
   cannot be active in two places.
2. Import it without printing the key:

   ```
   cli/import-entry-conf.sh ~/Downloads/your-entry.conf
   ```

   This writes the `ENTRY_*` keys into `~/.config/vpn-chain/secrets.env`.
3. In the app, **Settings → Chain identity → Import → Import from disk**, or paste
   the `.conf` and tap **Use as entry hop**.

**Settings → Routing → Wireguard entry hop** turns it off without editing the
profile.

## Using an external VPN app as the entry hop

Leave the entry fields blank and run the VPN app yourself. Its interface carries
sing-box's dial, and its kill switch protects the chain; see
[kill-switch.md](kill-switch.md). The desktop app recognises ProtonVPN by its
`proton0` interface.

Do not run that app alongside a configured WireGuard entry hop. Two VPNs fight
over the routing table, and the app's kill switch blocks sing-box. Before
connecting:

1. Disconnect the app (for ProtonVPN, `protonvpn disconnect`).
2. Check the default route is back on the physical interface: `ip route show default`.
3. Check no `pvpnksintrf0` interface lingers. If it does, a permanent kill switch
   is on; disable it in ProtonVPN.

## Troubleshooting

`cli/diagnose-tun.sh [out.txt]` writes TUN diagnostics to a file (default
`/tmp/vpn-chain-tun-diag.txt`) while the app is connected: the sing-box process,
the TUN device and MTU, routes, and the VLESS connection. It reads no secrets.
