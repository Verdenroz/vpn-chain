# Cloudflare WARP tail exit

Some sites refuse the relay VPS's datacenter address outright (Reddit and ChatGPT
both answer `403` through ours). The fix is one more hop after the relay, so those
sites see Cloudflare's address:

```
you → WireGuard entry hop → VLESS+REALITY relay VPS → Cloudflare WARP → internet
```

The tail is a WireGuard endpoint (tag `warp-exit`) with `detour` set to the relay
outbound: dialled through the chain, never around it. Cloudflare sees the relay's
address, never the device.

GUI only (Android and desktop), and only for a connection with a relay. The CLI
renders from `config-templates/` and still exits from the VPS. A WireGuard-only
connection has no tail.

## Choose a mode

**Settings → Routing → Cloudflare WARP exit.** Credentials register themselves on
first use: no `wgcf`, no account, nothing to paste.

| Mode | What goes through WARP |
|---|---|
| Off | Nothing; traffic exits from the VPS |
| Automatic (default) | Sites the chain [learns](#automatic-detection) refuse the relay |
| Everything | All traffic |

Settings saved by older versions are migrated: the manual domain list is dropped
and the old "blocked sites" mode becomes Automatic.

With Automatic, two more switches appear:

| Switch | Default | Effect |
|---|---|---|
| Learn blocked sites | On | Probe new destinations and route refused ones through WARP |
| Bypass the relay when WARP is also refused | On | [Fail open](#failing-open) for sites both refuse |

The **Learned** list under them shows each site, why it was refused, and which way
it leaves. **Remove** forgets a site and stops it being learned for seven days.

## What it costs

- **WARP does not anonymize you to Cloudflare.** The tail is dialled through the
  relay, so Cloudflare sees the VPS's address (unique, static, rented in someone's
  name) tied to a persistent registration and every destination it carries. It
  never sees the device, but it gets a durable pseudonym that resolves to a box
  with a billing record. Rotating the key changes nothing. That is why Automatic
  is the default: it confines that view to the domains that forced the issue.
- **The last hop is no longer yours in Everything mode.** Destination knowledge
  moves to a US company with more legal-process exposure than your VPS.
- **Shared addresses cut both ways.** Expect more CAPTCHAs, and some banks and
  streaming services block WARP ranges outright.
- **Sites can still say no.** ChatGPT moved from a hard `403` to intermittently
  allowed. Reddit refuses WARP too: `GET /` returns a proof-of-work interstitial,
  and the JSON endpoints and real browsers get its "blocked by network security"
  page. That is what [failing open](#failing-open) is for.
- **The tail handshakes before any site uses it** in Automatic mode, so Cloudflare
  sees the relay's address connecting. It learns no destinations from that.
- **Speed.** Everything mode measured within noise of the plain relay on a
  non-Cloudflare target, with better first-byte latency.

## Automatic detection

### What is watched

Destination names come from the engine's own connection data. Desktop polls
sing-box's clash API (`/connections`, every 2 seconds, so a shorter connection can
be missed). Android listens to libbox connection events (`CommandConnections`).

- Only sniffed names count. Bare addresses, local names and the app's own probe
  connections are ignored, as are names that are not plain host names.
- A site learned for WARP is reduced to its registrable domain under the bundled
  [Public Suffix List](https://publicsuffix.org/)
  (`core/warp/src/jvmShared/resources/vpnchain/public_suffix_list.dat`, MPL-2.0).
  The list's private section names shared hosts (`cloudfront.net`, `github.io`),
  so one tenant's refusal does not move the whole platform. A host that is itself
  on the list (`github.io`, `s3.amazonaws.com`) is learned as that host alone.
- A site that fails open or is sent direct is matched by its exact host
  (`domain`, not `domain_suffix`), since that route leaves the relay.
- Verdicts are cached per host, so one host answering says nothing about its
  siblings.
- Nothing is probed while the chain is down, on Android while the screen is off or
  the device is in Doze (names seen then wait in a bounded queue), or for a domain
  the user removed.

### How a site is probed

Each new domain is fetched once, `GET /` with a browser User-Agent, through
loopback-only `mixed` inbounds. sing-box renders them with a random port and
credentials per config: `probe-relay`, `probe-warp`, and `probe-fallback` while
failing open is on and there is an entry hop. Each fetch has a 15-second overall
deadline, so a site that drips its answer cannot hold a probe slot. At most two
probes run at once. Route rules on those inbounds sit ahead of every other rule,
so a probe exits where it asked to whatever has been learned.

1. The **relay** is probed first. It already sees every destination. If it
   answers, the site is left alone.
2. Only if the relay refuses outright, **WARP** is probed. A challenge on the relay
   also leaves the site alone, since a browser passes it.
3. If WARP answers **cleanly** (a 2xx or 3xx with no challenge), the site is
   learned for WARP and routed there.
4. Otherwise the [fail-open path](#failing-open) is probed.

A challenge on WARP counts as a refusal: WARP's shared address is the suspect, and
a plain client cannot use a challenge.

### How an answer is classified

`SiteClassifier` is a pure function over the response, tested against recorded
samples. These are heuristics.

| Verdict | Means |
|---|---|
| Blocked | `403`, `429` or `451`, or a block page ("blocked by network security") |
| Challenged | A page a real browser would pass: a `js_challenge` form field (Reddit's proof-of-work interstitial answers `200`), a Cloudflare challenge, or `cf-mitigated: challenge` |
| Open | Anything else below `500` |
| Inconclusive | A server error |

A block page outranks a challenge marker, and a challenge marker outranks the
status it came with. Markers count only in a page that arrived whole or came with
an error status, so a long article that quotes one is not a block.

### What each exit learns

| Exit | Sees a domain name when |
|---|---|
| Relay | Always, as the probe's connect target; it sees the destination anyway |
| WARP | Only after the relay refused it |
| Entry hop | Only after both refused it, and only while failing open is on |
| Bare link | Never probed |

### Cache and timing

| What | Value |
|---|---|
| Reachable verdict | Kept 12 hours |
| Inconclusive probe (timeout, server error) | Retried after 5 minutes |
| Saved to disk | Every 5 minutes and when detection stops; the most recent 2,000 domains |
| A learned site | Stays until the user removes it |
| A removed site | Not learned again for 7 days |

### How a learned site takes effect

Learned domains live in a local sing-box rule-set file (source format, version 1)
referenced by a route rule of its own. sing-box reloads a local rule-set when its
file changes (checked on 1.14.3 with a throwaway instance, including an atomic
rename), so a learned site applies to new connections without a reconnect.

The libbox 1.13.14 on Android contains the same file watcher, but it has not been
exercised on a device and the app cannot observe whether a reload fired. If it
does not, a learned site takes effect at the next reconnect, when the files are
rewritten before the config renders.

Files are written atomically under `/run/vpn-chain` (readable through the
`vpn-chain` group) or the user's config directory on desktop, and in libbox's
working directory on Android. A failed write costs the learned sites, never the
connect.

### Open connections move too

A route applies when a connection opens, and browsers keep reusing pooled
connections to a host. So whenever a site's route changes (learned for WARP or
failing open, sent direct, undone, or removed), the app closes the engine's open
connections to it a second after the files are written, past sing-box's 100 ms
reload debounce, and the browser redials under the new rule. A WARP entry at a
registrable domain closes the names under it too; every other change closes the
exact host. Nothing else is closed, nor are the app's probe connections.

- Desktop lists `/connections` on the clash API and deletes each match by id.
- Android tracks open connections from the libbox connection events detection
  already listens to, and closes each match through a standalone libbox command
  client. That stream only runs for a config with probe inbounds, so nothing is
  closed without one. Not yet exercised on a device.

A close that fails is logged once and costs nothing else: the old connection
keeps its route until it ends.

## Failing open

Some sites refuse the relay and WARP both. With **Bypass the relay when WARP is
also refused** on, the chain can send such a site past the relay. That is a
deliberate step out of the chain, so the Learned list shows which way each such
site leaves.

| Chain | Path | Who decides |
|---|---|---|
| With an entry hop | The entry hop alone | Automatic |
| Without one | The bare link (`direct`) | Only the user |

**With an entry hop.** The same probe runs down that path first. The site fails
open when the entry hop answers or merely challenges it. A site it blocks outright
(`403`, `429`, `451` or a block page) is recorded as refused on every path and
stays on the relay. The site then sees the entry provider's address.

**Without an entry hop.** Nothing goes to the bare link on its own. The site is
recorded as refused with the bare link available, is in no rule-set, and is never
probed through `direct`. Only **Send direct** on that site (on the Chain card or in
Settings; `LearnedSitesRepository.allowDirect`) adds it to the bare link's
rule-set, and no probe follows: the next page load is the test. **Undo**
(`forget`) takes it back. The site sees this network's own address. Desktop proxy
mode never dials an entry hop, so it always works this way. A later probe never
changes a host the user sent direct, or one failing open through the entry hop.

### Rules that keep it consistent

- The learner uses the fallback probe only when the running config routes
  `probe-fallback` to the entry hop and the active connection dials one.
- Changing failing open, the entry hop or the WARP mode while connected restarts
  the tunnel, so the app shows what is running.
- Two files hold the lists. `learned-open.json` holds sites failing open through
  the entry hop plus sites sent direct, and renders only with an entry hop.
  `learned-direct.json` holds only sites sent direct, and renders only without one.
  A site checked through one entry hop never reaches the bare link because the
  entry hop was later turned off.
- The relay still sees the DNS lookup for a site that fails open; only the
  connection leaves past it. In proxy mode the config carries its own DNS block
  over the relay whenever WARP or failing open can carry traffic. Without it,
  those connections would ask the local resolver.

### Kill switch

The entry peer is already exempt from the nftables table, so failing open to it
adds no exemption. On a chain with no entry hop, sing-box's `direct` outbound
carries a fixed packet mark the table accepts. That needs the kill-switch helper
rebuilt and `setcap` run on it once. See
[kill-switch.md](kill-switch.md#the-fail-open-mark) for the mark and its risks.

## Operations

### A lost port race

Probe and clash API ports are picked free just before sing-box binds them.
sing-box 1.14.3 fails its whole start when any inbound cannot bind (checked with a
throwaway instance on a busy port: `FATAL start service: start inbound: ... bind:
address already in use`). A start that fails that way on a loopback port this app
picked is rendered once more with fresh ports.

### An adopted relay from another build

The systemd relay outlives the app, including across an in-app update, and an older
build's config may send fail-open sites to `direct`. The app writes its render
schema to `/run/vpn-chain/relay.render` next to `relay.json`. On adoption, a
missing or different stamp means the relay is started again with a fresh render,
and the kill switch is never re-armed from such a config.

### Which session is watched

Detection reads the probe ports and credentials from the config the engine
actually runs (`TunnelController.runningConfig`), not from the last one rendered.
A relay adopted under systemd is read from its unit's config file. A relay adopted
from the CLI has no probe inbounds, so nothing is probed for it.

## Credentials

Registered against Cloudflare's free API the first time the tail is needed. The
key is stored in the app's datastore next to the chain profile and is a credential
like any other: never logged, never exported with the profile, never in the repo.

- Registration uses the **untunnelled** link, because it must happen before the
  tunnel it configures exists. It is an ordinary HTTPS request, and the one part of
  the chain that is not inside the chain.
- Free registrations last about 90 days. The app refreshes at 60. A refresh that
  fails keeps the existing key: the usual cause is a network that cannot reach
  Cloudflare at all, which says nothing about the key held.

### When it cannot register

The chain still comes up one hop shorter, exiting from the VPS. A blocked
registration never stops the tunnel. The exit hop on the Chain card reads
`vless · reality → warp` only when the measured exit address is not the relay's.
`vless · reality · tcp 443` means the tail is not carrying.

## Invariants

- The tail always carries `detour` to the relay outbound.
- The tail is never in the kill switch's exemption list. Its packets are already
  inside the tunnel when they reach the wire, so exempting Cloudflare's anycast
  address would open a hole nothing needs, one that stays open exactly when
  sing-box dies. The exemptions remain the VPS and, when configured, the entry
  peer.
- The probe inbounds listen on loopback only.
- Failing open adds no address to the exemption list.
- The tail is another 1280-byte WireGuard hop, so it caps the TUN MTU at 1280,
  including on a single-hop chain that would otherwise run at 1400.
