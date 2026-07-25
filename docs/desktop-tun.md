# Desktop TUN mode

TUN is the default: sing-box creates a TUN device and captures all system
traffic, doing both hops itself (`tun` → optional Proton WireGuard entry →
VLESS). The narrower alternative is SOCKS-proxy mode — only apps configured
to use `127.0.0.1:1080` are tunneled, and any entry hop is your own
responsibility to run. Toggle between them at **Settings → Routing → "Route
entire system (TUN)"**.

`SingBoxConfigFactory.renderPlatformTunnelConfig` picks the shape per
platform; desktop's TUN chain mirrors the CLI's
`you → Proton → VPS → internet` chain, so the GUI and CLI are functionally
equivalent.

## One-time privilege grant

TUN needs a capability grant on the `sing-box` binary. Run
`cli/setup-desktop.sh` to handle this, the kill-switch helper (see
`docs/kill-switch.md`), and the polkit rule below in one guided pass (safe to
re-run) — or just the sing-box grant by itself:

```
sudo setcap cap_net_admin,cap_net_bind_service=+ep $(command -v sing-box)
```

Without it, connect fails with `TUNSETIFF: operation not permitted` — the app
detects the missing capability before attempting to connect and shows this
exact command as the error.

TUN mode also asks `systemd-resolved` to set/revert DNS on the tunnel
interface on every connect and disconnect, which by default means a polkit
password prompt each time. `cli/setup-desktop.sh` installs a rule scoped to
just that (`cli/polkit/10-vpn-chain-resolve1.rules`) so it stops prompting.

## Adding the Proton entry hop

Generate a **dedicated** WireGuard config at account.protonvpn.com (a
*different* one than any other device uses — the same key can't be active in
two places) and import it without exposing the key:

```
cli/import-proton-entry.sh ~/Downloads/your-entry.conf   # → secrets.env
```

then in the app: **Settings → Import ~/.config/vpn-chain/secrets.env**. Or
type the fields into the Settings form's *TUN entry hop* section. Without an
entry the TUN chain is relay-only.

## Conflict with the real ProtonVPN app

If you also run the CLI's local ProtonVPN entry hop (the real app, not the WG
config above), disconnect it before using desktop TUN mode
(`protonvpn disconnect`) — sing-box now dials Proton itself via the
WireGuard entry, so a separate Proton connection fights over the routing
table, and its kill switch would block sing-box's traffic. Afterwards confirm
your default route is back on your physical interface
(`ip route show default`) and no `pvpnksintrf0` kill-switch interface
lingers; if it does, you have a permanent kill switch enabled — disable it in
ProtonVPN before connecting.
