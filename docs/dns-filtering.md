# DNS filtering

A commercial VPN's DNS filter runs at that provider's own resolver, so it
only exists while they are the entry hop. A single-hop chain resolves through the relay
instead — which means the filtering has to happen on our side of the wire.

**Settings → dns → Block unwanted domains.** On by default (at the full
level), on both platforms, in every mode that renders a TUN chain. The
selector offers two levels:

- **threats** — malware, phishing, and scam domains;
- **threats + ads** — advertising, tracking, and telemetry domains on top
  (the default).

## How it works

The rendered config gains two things: `route.rule_set` entries — compiled
binary `.srs` files sing-box loads directly — and one `dns.rules` entry
matching all of them with `action: reject`. The full level ships three sets:

| set | source | covers |
|---|---|---|
| `blocklist-threats` | [hagezi TIF medium](https://github.com/hagezi/dns-blocklists) | malware, phishing, scam |
| `blocklist-ads-trackers` | hagezi Pro++ | ads, trackers, telemetry |
| `blocklist-ads` | sing-box's v2fly geosite mirror | apex ad domains hagezi spares (e.g. `doubleclick.net`) |

The threats level ships only the first. Together the full level scores
**99% (130/131) on [d3ward's adblock test](https://d3ward.github.io/toolz/adblock)**
— the one miss, `udc.yahoo.com`, appears only in hagezi's Ultimate tier,
whose known breakage (parts of Facebook/WhatsApp) isn't worth one test
domain. Pro++ was chosen over Pro (98.5%) because this app has no
per-domain allowlist; hagezi curates Pro++ for minimal breakage, and the
whole filter can be turned off in one tap if a site misbehaves.

## Who compiles the lists

hagezi publishes AdGuard-format text, not sing-box's binary form, and the
third-party repos that convert it are exactly the unvetted intermediary
this file used to warn about. So we compile it ourselves:
`.github/workflows/blocklists.yml` fetches the lists weekly, converts them
with a pinned sing-box (`rule-set convert --type adguard`), gates on canary
checks (known-bad domains must match, `github.com` must not, rule counts
must be plausible), and publishes to the repo's rolling `blocklists`
release. The app trusts two parties it already had to trust: hagezi for
list content, this repo's CI for compilation. The geosite set comes
straight from the sing-box project — the upstream that ships the binary we
run.

A matching lookup is refused outright rather than answered with a black-hole
address, so callers fail immediately instead of waiting out a connection to
an address that goes nowhere.

- **Fetched through the chain.** `download_detour` is the relay outbound, so
  blocklist requests leave from the VPS, not from your link. A blocklist
  fetched around the tunnel would announce the app to the network the chain
  exists to hide it from.
- **Cached.** `experimental.cache_file` keeps the compiled set between runs
  and refreshes it weekly. Desktop pins the path to `~/.config/vpn-chain/`;
  sing-box's default is relative to the working directory, which for a login
  item is `/`. Android leaves it to libbox's own working directory.
- **One `experimental` block.** sing-box takes only one, so the cache and the
  clash API used for the stats readout are rendered together — writing them
  separately silently drops whichever came first.

## What it does and doesn't cover

DNS filtering blocks whole hostnames, so it catches what any resolver-side filter catches:
third-party ad servers, trackers, telemetry endpoints, and known-malicious
domains. Like any DNS-level filter, it cannot remove first-party ads served from the
same hostname as the content (YouTube ads, Facebook's feed) — that takes a
content-inspecting browser extension, which no DNS filter replaces. If you
have an extra list you trust, it is one entry in `blocklistsFor` in
`SingBoxConfigFactory`.

Desktop's proxy mode (system-wide TUN off) renders no resolver of its own —
the VLESS outbound forwards hostnames to the VPS, which resolves them — so
the toggle is hidden there rather than shown doing nothing.
