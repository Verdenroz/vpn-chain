# Cloudflare WARP tail exit

The relay VPS is a datacenter address, and some sites refuse those outright —
not the chain, not the protocol, just the address's reputation. Reddit and
ChatGPT both answer `403` through ours. The fix is to add one more hop *after*
the relay, so what those sites see is Cloudflare's address instead:

```
you → WireGuard entry hop → VLESS+REALITY relay VPS → Cloudflare WARP → internet
```

The tail is a WireGuard endpoint (tag `warp-exit`) with `detour` set to the
relay outbound, which is the whole design in one line: the tail is dialled
*through* the chain, never around it. Nothing upstream changes, and Cloudflare
sees the relay's address — never the device's.

## Modes

**Settings → routing → Cloudflare WARP exit**, three stops:

| Mode | What routes through WARP |
|---|---|
| `off` | Nothing. Traffic exits from the VPS, as it did before. |
| `automatic` | Sites the chain [learns](#automatic-detection) refuse the relay. **The default.** |
| `everything` | All traffic. |

There is no hand-kept domain list: detection replaces it. An earlier version
had one, seeded with Reddit's and ChatGPT's domains. The stored list is
dropped on first launch, whatever it held, and the old `blocked sites` mode
reads as `automatic`.

`everything` costs nothing measurable in speed — WARP rides Cloudflare's
backbone, and on a non-Cloudflare target throughput was within noise of the
plain relay while first-byte latency improved. It is not the default anyway;
see the trade below.

## What it costs

- **WARP does not anonymize you to Cloudflare.** The tail is dialled through
  the relay, so what Cloudflare sees is the VPS's address — unique, static,
  and rented in someone's name — attached to a persistent registration and to
  every destination it carries. It never sees the device, but it gets a
  durable pseudonym that resolves back to a box with a billing record.
  Rotating the key changes nothing; the source address is the constant.
  This is why `automatic` is the default: it confines that view to the
  domains that actually forced the issue.
- **The last hop is no longer a box you own.** In `everything` mode,
  destination knowledge moves off your VPS and onto a US company with far
  more legal-process exposure.
- **Shared-IP reputation cuts both ways.** WARP addresses are shared by
  everyone using WARP. Some sites are stricter with them than with a quiet
  VPS: expect more CAPTCHAs, and some banks and streaming services block WARP
  ranges outright. That is what `automatic` and `off` are for.
- **Sites can still say no.** ChatGPT went from a hard `403` to intermittently
  allowed on the shared address. Reddit refuses WARP too: a plain `GET /`
  returns a proof-of-work interstitial, and the JSON endpoints and a real
  browser get its "blocked by network security" page. Routing alone does not
  fix it, which is what [failing open](#failing-open) is for.
- **The tail is declared whenever detection is on.** The WireGuard peer keeps a
  handshake alive through the relay, so Cloudflare sees the relay's address
  connecting even while no site uses the tail. It learns nothing about
  destinations from that.

## Automatic detection

With **Learn blocked sites** on (the default, in `automatic` mode), the
chain finds the sites that refuse the relay without a list.

**What is watched.** Destination names come from the engine's own connection
data: desktop polls sing-box's clash API (`/connections`, every two seconds,
so a connection shorter than that can be missed), and Android listens to
libbox's connection events (`CommandConnections`). Only sniffed names count;
bare addresses, local names and the app's own probe connections are ignored.
Names are reduced to a registrable domain with a short list of country
second levels (`example.co.uk`), not the public suffix list, so a rule may
come out broader or narrower than ideal.

**How a site is probed.** Each new domain is fetched once, `GET /` with a
browser User-Agent, through loopback-only `mixed` inbounds that sing-box renders with a random
port and credentials per config (`probe-relay`, `probe-warp`, and
`probe-fallback` while failing open is on). Route rules on those inbounds sit
ahead of every other rule, so a probe exits where it asked to whatever has
been learned. At most two probes run at once. Nothing is probed while the
chain is down, on Android while the screen is off or the device is in Doze
(names seen then wait in a bounded queue), or for a domain the user removed.

1. The **relay** is probed first. The relay already sees every destination,
   so this tells it nothing new. If it answers, the site is left alone.
2. Only if the relay refuses outright, **WARP** is probed. A challenge on the
   relay leaves the site alone: a browser passes it. Cloudflare
   sees that destination only at that point, and the relay's address, never
   the device.
3. If WARP answers **cleanly** (a 2xx or 3xx with no challenge), the site is
   **learned for WARP** and routed there.
4. Otherwise the **fail-open path** is probed (see [failing open](#failing-open)).

**What the classifier says.** Each answer is one of four things. *Blocked* is a
`403`, `429` or `451`, or a block page ("blocked by network security").
*Challenged* is a challenge page a real browser would pass: a `js_challenge`
form field (Reddit's proof-of-work interstitial answers `200`), a Cloudflare
challenge, or `cf-mitigated: challenge`. A block page outranks a challenge
marker, and a challenge marker outranks the status it came with. *Open* is
anything else below `500`, and a server error is inconclusive. Markers are only
read in a page that arrived whole or came with an error status, so a long
article that quotes one is not a block. The rules are a pure function over the
response (`SiteClassifier`) tested against recorded samples. They are
heuristics.

A challenge on WARP does not make a site reachable there: WARP's shared address
is the suspect, and a challenge is not an answer a plain client can use. So
WARP takes a site only on a clean answer, and a challenge on WARP counts as a
refusal.

**What leaks to which exit.** A domain name reaches the relay as the probe's
connect target (it sees the destination anyway), reaches WARP only after the
relay refused it, and reaches the fail-open path only after both did and only
while failing open is on. Probe traffic goes nowhere else.

**The cache.** A reachable verdict is kept for 12 hours and persisted (the
most recent 2,000 domains), so a restart or Android killing the process does
not re-probe everything. An inconclusive probe (a timeout, a server error) is
retried after 5 minutes. A learned site stays until the user removes it;
removing one stops it being learned again for seven days.

**How a learned site takes effect.** Learned domains live in a local sing-box
rule-set file (source format, version 1) referenced by a route rule of its
own. sing-box reloads a local rule-set when its file changes (checked on 1.14.3 with a
throwaway instance, including an atomic rename), so a learned site applies to
new connections without a reconnect. The libbox 1.13.14 shipped on Android
contains the same file watcher, but that has not been exercised on a device,
and the app cannot observe whether a reload fired. If it does not, a learned
site takes effect at the next reconnect, when the files are rewritten before
the config is rendered. The files are written atomically under
`/run/vpn-chain` (readable through the `vpn-chain` group) or the user's
config directory on desktop, and in libbox's working directory on Android. A
failed write costs the learned sites, never the connect.

## Failing open

Some sites refuse the relay and WARP both. **Fail open when both are
refused** (on by default) lets the chain send such a site past the relay, to
the **entry hop alone**, or to the **bare link** (`direct`) when the profile
has no entry hop. The site then sees the entry provider's address, or this
network's own address, instead of the relay's. That is a deliberate step out
of the chain, so the app says so per site: the learned-sites list and the
chain state (`OutsideChainSite`) carry which way each site leaves.

Before a site moves, the same probe is run down that path, and the site fails
open when that path answers or merely challenges it, since a real browser
passes a challenge from an ordinary address. A site the path blocks outright (a
`403`, `451` or a block page) is recorded as refused on every path and stays on
the relay. That check is what keeps a site refusing every non-browser client,
whatever its source address, from being moved for nothing. The fail-open probe
inbound only exists while the setting is on.

The relay still sees the DNS lookup for a site that fails open; only the
connection leaves past it.

**Kill switch.** The entry peer is already exempt from the nftables table, so
failing open to it adds no exemption. The bare link is not: on desktop the
kill switch rejects sing-box's own direct packets, so with it armed a
relay-only chain's fallback probe fails and the site stays on the relay. No
exemption is added for it; see [kill-switch.md](kill-switch.md).

**Invariants.** The tail always carries `detour` to the relay outbound and is
never in the kill switch's exemption list. The probe inbounds listen on
loopback only.

## Credentials

Registered automatically against Cloudflare's free API the first time the tail
is needed — no `wgcf`, no account, nothing to paste. The key is stored in the
app's datastore next to the chain profile, and is a credential like any other:
never logged, never exported with the profile, never in the repo.

Registration talks to Cloudflare over the **untunnelled** link, because it has
to happen before the tunnel it configures exists. That is an ordinary HTTPS
request, but it is the one part of the chain that isn't inside the chain.

Free registrations last about 90 days; the app refreshes at 60, well before a
lapse could bite. A refresh that fails keeps the existing key rather than
discarding it — the usual reason registration fails is a network that can't
reach Cloudflare at all, which says nothing about the key already held.

## When it can't register

The chain still comes up — one hop shorter, exiting from the VPS. A blocked
registration must never be the reason the tunnel won't start. You can tell
which happened from the chain screen: the exit hop reads
`vless · reality → warp` only when the measured exit address is genuinely not
the relay's. If it says `vless · reality · tcp 443`, the tail isn't carrying.

## Kill switch

The tail is deliberately **not** in the kill switch's exemption list. Its
packets are already inside the tunnel by the time they reach the wire, so
exempting Cloudflare's anycast address would open a hole nothing needs — one
that would stay open exactly when sing-box dies. See
[kill-switch.md](kill-switch.md); the exemptions remain the VPS and, when
configured, the entry peer.

## MTU

The tail is another 1280-byte WireGuard hop, so it caps the TUN at 1280 the
same way an entry hop does — including on a single-hop chain, which would
otherwise run at 1400.

## Scope

GUI only (Android and desktop). The bash CLI in `cli/vpn-chain` renders from
`config-templates/` and is unchanged — it still exits from the VPS.
