# Desktop kill switch

Every desktop mode has a kill switch, from one of two sources. Android uses the
system Always-on VPN instead: [android.md](android.md#kill-switch).

| Mode | Kill switch | Setup |
|---|---|---|
| WireGuard entry hop (sing-box dials it) | The app's nftables table | The helper below |
| Single hop (no entry, no external VPN app) | The app's nftables table, exempting only the VPS | The helper below |
| No entry fields, external VPN app carries the hop | The VPN app's own | Enable it in that app |
| Proxy mode (**Route entire system** off) | None: nothing is captured system-wide, so there is no leak to catch | none |

The Chain card's **Kill switch** row reports what actually holds, not the mode
you configured: **On**, or **Off** with a hint naming the cause: set up in
Settings, helper not installed, helper not privileged (run `setcap` again after
every build), helper failed or timed out, no relay address to exempt, not armed
this session, or provided by your system VPN app. The app cannot enable another
app's kill switch for you.

## Install the helper

1. Build it and grant the capability (or run `cli/setup-desktop.sh`, which does both):

   ```
   cli/killswitch/build.sh
   sudo setcap cap_net_admin=eip cli/killswitch/vpn-chain-killswitch
   ```

   `build.sh` needs `gcc` and the libcap headers.
2. Put it on `PATH` as `vpn-chain-killswitch`:

   ```
   ln -s "$PWD/cli/killswitch/vpn-chain-killswitch" ~/.local/bin/
   ```

3. Check it: `vpn-chain status` prints `kill switch: helper ready`, `helper not
   installed` or `helper not privileged`.

On by default. **Settings → Kill switch → Fail closed** turns it off.
**Allow local networks** (on by default) keeps LAN and Docker reachable; turning
it off is strictly fail-closed.

If the helper is missing or unprivileged, the app warns and connects without the
safety net.

### After upgrading the helper

A helper from before `--allow-mark` rejects it with exit 2, a usage error; the
current helper exits 3 for an invalid value, which is never retried. The app then installs
the table without the mark and logs that sites failing open stay blocked. The
kill switch stays up; only failing open to the bare link is lost. To get it back,
rebuild and re-grant, once:

```
cli/killswitch/build.sh
sudo setcap cap_net_admin=eip cli/killswitch/vpn-chain-killswitch
```

## The table

The app installs an nftables table on the `OUTPUT` hook before connecting. It
accepts, in order, then rejects everything else:

| Accept | Why |
|---|---|
| Loopback | Local services |
| Anything routed to `tun0` | Traffic sing-box is already carrying |
| The VPS and entry-peer addresses | sing-box's own uplink |
| RFC1918 and link-local (with **Allow local networks**) | LAN, Docker bridges, printers |
| Packets with the fail-open mark (see below) | Sites sent past the relay |

`tun0` is sing-box's default TUN name. The TUN also carries a matching
`route_exclude_address` list with **Allow local networks**, so `auto_route` does
not pull those destinations into `tun0` and swallow the replies.

The helper usage:

```
vpn-chain-killswitch up [--allow-local] [--allow-mark <hex>] <exempt_ip>...
vpn-chain-killswitch down
vpn-chain-killswitch status
```

`status` exits 0 when the table is installed and 1 when it is not. `up` exits 2
for a usage error or unknown option and 3 for an invalid address or mark.

### Why it is built this way

- **Firewall, not routing.** sing-box's `auto_detect_interface` watches the
  routing table to find the real interface. A route with no interface attached,
  such as a blackhole route, confuses it and breaks sing-box's own uplink.
- **`reject`, not `drop`.** Other apps fail at once instead of hanging until
  their own timeout. Neither lets the packet out.
- **The `tun0` exemption is needed.** Traffic sing-box captures for relay has a
  real destination (the site), not the VPS. Without the exemption the rule blocks
  ordinary browsing. It is safe because it stops matching the instant sing-box
  dies and `tun0` goes away; leaked traffic then falls to the physical link, where
  only the VPS and entry IPs still pass.
- **Local networks.** Without that exemption the `reject` resets host-to-container
  traffic that never reaches the physical link, and a container the host cannot
  reach looks like a crashed service.
- **Torn down on Disconnect, not on a crash.** An unexpected crash is exactly
  when it must keep blocking. `vpn-chain down` writes
  `$XDG_RUNTIME_DIR/vpn-chain/down-requested` before stopping the relay; a
  running GUI treats that as the user disconnecting (no reconnect), and the next
  `vpn-chain up` or GUI connect clears it.
- **One transaction.** `up` renders the whole ruleset, the final `reject` and the
  mark rule included, into one nft script and loads it with a single `nft -f -`.
  The script deletes any old table and re-creates it in the same commit, so a
  re-arm never leaves a window with no `reject`, and a failed load leaves the
  previous table rather than a half-built one.
- **Known state after a failure.** A failed or timed-out `up` (a timeout also
  kills the helper's `nft` child) leaves the previous table in place. The app asks
  `status` whether a table exists and records that, and never tears one down on
  failure, which would fail open on a re-arm.
- **No shell.** `nft` is `execve`d by absolute path with a fixed argv, the script
  on a pipe, and a fixed environment (`PATH`, `LC_ALL=C`). Exempt IPs are strict
  dotted quads (octets 0-255, no leading zeros) and the mark a strict hex value,
  both checked before the script is written. The helper raises `CAP_NET_ADMIN`
  into its own ambient set and execs `nft`, so `nft` never needs a capability
  grant.
- **Stands down for an external VPN app.** The app detects ProtonVPN's `proton0`
  and does not install its own table: it would blackhole the very tunnel it
  rides, because the rendered config never lists that app's WireGuard endpoint
  as an exemption.

## The fail-open mark

A site the user sent direct on a chain with no entry hop leaves by sing-box's
`direct` outbound, onto the physical link, which the table would reject. So that
outbound alone carries `routing_mark` `0x760000`, and the helper adds
`meta mark <hex> accept` ahead of the final reject.

- Rendered only on desktop, in `automatic` mode with failing open on, no entry
  hop, and the sent-direct rule-set present. With an entry hop, the fallback goes
  through the entry peer, which is already exempt. Android never renders it.
- The mark avoids sing-box's auto_redirect marks (`0x2023` to `0x2025`),
  WireGuard quick (51820), Kubernetes (`0x4000`, `0x8000`) and Tailscale
  (`0x40000`, `0x80000` under mask `0xff0000`).
- The helper accepts only a `0x`-prefixed, nonzero value of up to eight hex
  digits, and writes it into the script as `meta mark <hex> accept` just before
  the `reject`.

Risks:

- sing-box stamps the mark, so the hole closes when sing-box dies, unlike an
  address exemption.
- Any other process holding `CAP_NET_ADMIN` can set the same mark and leak past
  the table. Ordinary apps cannot.
- The rule exempts everything on the `direct` outbound. That is safe only while
  failing open is its sole use.
- It does not change what the site sees: your real network address, with no kill
  switch behind those flows.

See [warp-exit.md](warp-exit.md#failing-open).

## With an external VPN app

Leave the entry fields blank and run the app with its own kill switch enabled.
sing-box's dial rides the default route, which is the app's tunnel, so the app's
kill switch protects it like any other app. No custom code is involved.
