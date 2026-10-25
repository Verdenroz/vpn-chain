# Desktop kill switch

Desktop gets a real kill switch in every mode, through one of two mechanisms:
the app's own nftables helper for any TUN chain it dials itself, or the real
external VPN app's kill switch when that app is carrying the entry hop.

## Relay-only mode, with an external VPN app

Leave the entry-hop fields blank and run an external VPN app with its own
kill switch enabled. sing-box's VLESS dial just rides whatever the OS default
route is — since that's the app's tunnel, the app's kill switch protects it
exactly as if it were any other app on your system. No custom code involved.

The app detects this (ProtonVPN's `proton0` is up — the one external client
with a fixed interface name) and stands down rather than installing its own
table: the rendered config never lists that app's WireGuard endpoint as
an exemption, so our rule would blackhole the very tunnel it is riding.

## Single-hop mode

Leave the entry-hop fields blank and *don't* run an external VPN app, and the
chain is you → relay VPS → internet. Neither of the above applies — there is
no external kill switch to inherit — so the app installs its own table, exactly
as below but exempting only the VPS. Enabled by the same **Settings → kill
switch** toggle, and it needs the same helper.

## WireGuard entry hop mode

When a WireGuard entry hop is configured (see `docs/desktop-tun.md`),
sing-box dials the entry peer itself, so there's no external app/tunnel for a kill
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
loopback, anything already routed to `tun0`, the VPS/entry-endpoint
exemptions, and (by default) local networks.

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
  to the physical link, where only the VPS/entry IPs still get through and
  everything else is rejected.
- **Direct traffic is not exempt.** sing-box's own `direct` outbound dials the
  destination on the physical link, which the table rejects like any other
  packet. That matters only to [failing open](warp-exit.md#failing-open) on a
  chain with no entry hop, whose fallback path is `direct`: with the kill
  switch armed the path does not carry, so such sites stay on the relay. No
  exemption is added for it. Failing open to an entry hop needs none, since
  the entry peer is already exempt.
- **Local networks are exempt by default.** RFC1918 and link-local
  destinations are accepted, so Docker bridges, LAN hosts and printers stay
  reachable while the chain is up. Without it the `reject` RSTs
  host-to-container traffic that never reaches the physical link in the first
  place, and a container the host cannot talk to looks exactly like a crashed
  service. The chain's TUN carries a matching `route_exclude_address` list so
  `auto_route` doesn't pull those same destinations into `tun0` and swallow
  the replies. **Settings → Allow local networks** turns both off together for
  a strictly fail-closed switch.
- **Torn down on disconnect, not on crash.** On Disconnect the app tears the
  table down — but *not* on an unexpected crash, since that's exactly when
  it's supposed to keep blocking traffic. `vpn-chain down` writes
  `$XDG_RUNTIME_DIR/vpn-chain/down-requested` before stopping the relay; a
  running GUI treats that as the user disconnecting (intent cleared, no
  reconnect), and the next `vpn-chain up` or GUI connect clears the marker.
- **One transaction.** `up` renders the whole ruleset, final `reject`
  included, into a single nft script and loads it with one `nft -f -`. The
  script deletes any existing table and re-creates it in the same commit, so
  re-arming never opens a window where the old table is gone and the new one
  has no `reject` yet, and a failed load leaves the previous table in place
  rather than a half-built one.
- **No shell.** `nft` is `execve`d by absolute path with a fixed argv, the
  script on a pipe, and a fixed minimal environment (`PATH`, `LC_ALL=C`), so
  nothing the caller exported reaches a process running with
  `CAP_NET_ADMIN`. Exempt IPs are strict dotted quads (octets 0-255, no
  leading zeros) and are written into the script only after validation. The
  helper raises `CAP_NET_ADMIN` into its own ambient set, so `nft` itself
  never needs any capability grant.

- **Known state after a failed `up`.** The load is atomic, so a failed `up`
  or an app timeout (which also kills the helper's `nft` child) leaves the
  previous table untouched. The app asks the helper's `status` whether a table
  exists and records that, and never tears one down on failure, since that
  would fail open on a re-arm.

If the helper isn't built/setcapped yet, the app warns and connects anyway
without the safety net.

## Status

The app's **Chain** screen shows `kill switch: protected` when either setup
is active and detected, or a warning when it isn't — reported from what
actually holds, not from which mode was configured. There's no way for the
app to enable an external VPN app's own kill switch for you — turn that one
on in that app's own settings.

Desktop's proxy mode (**Settings → routing**, system-wide TUN off) is the one
case with no fail-closed option of ours: nothing is captured system-wide, so
there is no leak for a firewall to catch. The status says so rather than
claiming protection.
