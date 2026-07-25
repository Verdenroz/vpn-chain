# Desktop kill switch

Desktop gets a real kill switch either way, but through one of two different
mechanisms depending on which mode you pick.

## Relay-only mode

Leave the entry-hop fields blank and run the real ProtonVPN app with its own
kill switch enabled. sing-box's VLESS dial just rides whatever the OS default
route is — since that's Proton's tunnel, Proton's kill switch protects it
exactly as if it were any other app on your system. No custom code involved.

## WireGuard entry hop mode

When a WireGuard entry hop is configured (see `docs/desktop-tun.md`),
sing-box dials Proton itself, so there's no real Proton app/tunnel for a kill
switch to attach to. Instead, build the narrow helper in `cli/killswitch/`
(or run `cli/setup-desktop.sh`, which does this step too):

```
cli/killswitch/build.sh
sudo setcap cap_net_admin=eip cli/killswitch/vpn-chain-killswitch
```

then put `vpn-chain-killswitch` on `PATH`. Enabled by default — flip
**Settings → Kill switch** off if you'd rather connect without it.

### How it works

Before connecting, the app installs an **nftables** table on the `OUTPUT`
hook — not a routing-table change — that rejects all outbound traffic except
loopback, anything already routed to `tun0`, and the VPS/Proton-endpoint
exemptions.

- **Firewall, not routing.** sing-box's own `auto_detect_interface` watches
  the routing table via netlink to find "the real" interface; a route with no
  interface attached (e.g. a blackhole route) would confuse that detection
  and break sing-box's own uplink. A firewall rule leaves the routing table
  untouched.
- **`reject`, not `drop`.** Other apps on the system get an immediate failure
  instead of hanging until their own timeout; the original packet never
  leaves the machine either way.
- **The `tun0` exemption matters.** Without it, the rule would block ordinary
  browsing traffic too, not just leaks — anything sing-box's TUN is actively
  capturing to relay has a real destination IP (the site you're visiting),
  not the VPS, so it needs its own exemption distinct from sing-box's own
  uplink. That's safe because the exemption stops applying the instant
  sing-box crashes and `tun0` stops existing; leaked traffic then falls back
  to the physical link, where only the VPS/Proton IPs still get through and
  everything else is rejected.
- **Torn down on disconnect, not on crash.** On Disconnect the app tears the
  table down — but *not* on an unexpected crash, since that's exactly when
  it's supposed to keep blocking traffic.
- **No shell.** Every `nft` invocation uses a fixed argv array (no shell, no
  injection surface) with strictly validated IPv4 arguments; the helper
  raises `CAP_NET_ADMIN` into its own ambient set and execs the ordinary
  `nft` binary, so `nft` itself never needs any capability grant.

If the helper isn't built/setcapped yet, the app warns and connects anyway
without the safety net.

## Status

The app's **Chain** screen shows `kill switch: protected` when either setup
is active and detected, or a warning when it isn't. There's no way for the
app to enable ProtonVPN's own kill switch for you — turn that one on in the
ProtonVPN app/CLI's own settings.
