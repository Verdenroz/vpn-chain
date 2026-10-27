# DNS filtering

The chain's own resolver refuses known-bad domains. A commercial VPN filters at
its own resolver, which only exists while it is the entry hop; a single-hop chain
resolves through the relay, so the filtering has to happen client-side.

## Turn it on or change the level

**Settings → DNS → Block harmful sites**, on both platforms:

| Level | Blocks |
|---|---|
| Off | Nothing |
| Threats | Malware, phishing and scam domains |
| Threats + ads | Threats, plus advertising, tracking and telemetry (default) |

It applies to every TUN chain. Desktop proxy mode (**Route all apps** off)
renders no resolver of its own, since the VLESS outbound forwards hostnames to
the VPS, so the card is hidden there. If a site misbehaves, turn the filter off
and reconnect.

## What each level loads

The rendered config gains `route.rule_set` entries (compiled binary `.srs` files
sing-box loads directly) and one `dns.rules` entry matching all of them with
`action: reject`.

| Set | Source | Covers | Level |
|---|---|---|---|
| `blocklist-threats` | [hagezi TIF medium](https://github.com/hagezi/dns-blocklists) | Malware, phishing, scam | Threats, Threats + ads |
| `blocklist-ads-trackers` | hagezi Pro++ | Ads, trackers, telemetry | Threats + ads |
| `blocklist-ads` | sing-box's v2fly geosite mirror | Apex ad domains hagezi spares (`doubleclick.net`) | Threats + ads |

Threats + ads scores **99% (130/131) on
[d3ward's adblock test](https://d3ward.github.io/toolz/adblock)**. The one miss,
`udc.yahoo.com`, is only in hagezi's Ultimate tier, whose known breakage (parts of
Facebook and WhatsApp) is not worth one test domain. Pro++ beat Pro (98.5%)
because the app has no per-domain allowlist, and hagezi curates Pro++ for
minimal breakage.

A matching lookup is refused outright, not answered with a black-hole address, so
callers fail at once instead of waiting out a connection to nowhere.

## How the lists are built

hagezi publishes AdGuard-format text, not sing-box's binary form. This repo
compiles it: `.github/workflows/blocklists.yml` runs weekly (Monday 03:17 UTC),
converts the lists with a pinned sing-box (`rule-set convert --type adguard`),
gates on canary checks (known-bad domains must match, `github.com` must not, rule
counts must be plausible), and publishes to the repo's rolling `blocklists`
release.

The app therefore trusts two parties: hagezi for list content, and this repo's CI
for compilation. The geosite set comes straight from the sing-box project, which
ships the binary the app runs.

## Where the lists go

- **Fetched through the chain.** `download_detour` is the relay outbound, so
  blocklist requests leave from the VPS. Fetching around the tunnel would
  announce the app to the network the chain hides it from.
- **Cached.** `experimental.cache_file` keeps the compiled sets between runs and
  refreshes them weekly. Desktop pins it to `~/.config/vpn-chain/`, because
  sing-box's default is relative to the working directory, which is `/` for a
  login item. Android uses libbox's working directory.
- **One `experimental` block.** sing-box takes only one, so the cache and the
  clash API used for the stats readout render together. Writing them separately
  silently drops whichever came first.

## What it does not cover

DNS filtering blocks whole hostnames: third-party ad servers, trackers, telemetry
and known-malicious domains. It cannot remove first-party ads served from the same
hostname as the content (YouTube ads, Facebook's feed). That takes a
content-inspecting browser extension.

To add a list you trust, add one entry to `blocklistsFor` in
`SingBoxConfigFactory`.
