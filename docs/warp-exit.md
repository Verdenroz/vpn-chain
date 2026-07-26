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
| `blocked sites` | Exactly the domains listed in the settings field. Suffix matches, so `example.com` covers its subdomains. |
| `everything` | All traffic. **The default.** |

The domain field is seeded with the sites known to refuse the relay's address
(Reddit's and ChatGPT's), and is then an ordinary editable list: nothing is
merged in underneath it, so deleting an entry really does stop routing it, and
an empty list renders no tail at all. Paste what a URL bar shows —
`https://Example.com/path` and `*.example.com` both normalise to
`example.com`.

`everything` is the default because it measured no slower — WARP rides
Cloudflare's backbone, and on a non-Cloudflare target throughput was within
noise of the plain relay while first-byte latency improved. Drop to
`blocked sites` if you'd rather keep the VPS as the exit of record.

## What it costs

- **The last hop is no longer a box you own.** Cloudflare sees every
  destination you reach in `everything` mode. It does *not* see your device —
  it sees the relay — but "no third party knows where I go" is no longer true.
  `blocked sites` narrows that to the domains you name.
- **Shared-IP reputation cuts both ways.** WARP addresses are shared by
  everyone using WARP. Some sites are stricter with them than with a quiet
  VPS: expect more CAPTCHAs, and some banks and streaming services block WARP
  ranges outright. That is what `blocked sites` and `off` are for.
- **Sites can still say no.** WARP fixed Reddit reliably in testing; ChatGPT
  went from a hard `403` to intermittently allowed on the same shared address.

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
