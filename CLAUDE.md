# CLAUDE.md

Guidance for coding agents. vpn-chain is `you → WireGuard entry hop →
VLESS+REALITY relay VPS → internet`, as a bash CLI (`cli/vpn-chain`) and a Kotlin
Multiplatform app (Android and desktop) sharing one core. Real credentials never
live in the repo: read the README's "Set up a connection" before touching
anything that reads config.

## Commands

```
./gradlew :desktopApp:run                    # desktop app
./gradlew :androidApp:assembleDebug          # Android debug APK
./gradlew test                               # all commonTest and desktopTest
./gradlew :core:config:test                  # one module
./gradlew :core:config:desktopTest --tests "*.SingBoxConfigFactoryTest"   # one class
./gradlew :desktopApp:installDesktopEntry    # launcher entry for a source build
```

- Gradle must run on JDK 17 to 21. `gradle.properties` pins
  `org.gradle.java.home`; on a JDK version error, check that path first.
- Tests run on the `desktopTest` JVM source set. There is no instrumented Android suite.
- Release builds are CI only (`.github/workflows/release.yml`, on a `v*` tag).
  The version lives in `vpnchain.version` in `gradle.properties`.
- Namespace: `com.verdenroz.vpnchain.<layer>.<module>`.

## Modules

| Module | Role |
|---|---|
| `core/model` | Data classes |
| `core/config` | `SingBoxConfigFactory`: profile to sing-box JSON; `renderPlatformTunnelConfig` per platform |
| `core/datastore` | Profile and settings persistence |
| `core/tunnel` | `TunnelController`: Android `VpnChainService` (libbox), desktop `sing-box` process |
| `core/warp` | WARP registration, site probing and classification |
| `core/update` | In-app updater |
| `core/relay` | Pairing link, `ManagementKey`, and the signed `RelayAgent` client that manages the relay through the tunnel |
| `core/data`, `core/domain` | Repositories, use cases, `ChainSupervisor` |
| `core/designsystem`, `core/ui` | Theme, map, shared composables |
| `feature/{chain,sites,settings,logs}` | Compose screens and ViewModels |
| `app-common` | Shell, nav, Koin aggregation |
| `androidApp`, `desktopApp` | Platform entry points |

Also `core/common` (app scope, dispatchers, tray, autostart), `core/logging`,
`core/geoip` (bundled range table for the map). Apply a convention plugin from
`build-logic/convention/` to a new module instead of configuring Gradle by hand.

Outside Gradle: `cli/vpn-chain` (bash CLI), `cli/*.sh` with `cli/systemd`,
`cli/polkit` and `cli/killswitch/` (desktop install and setup), `server/` (VPS
provisioning, devices, rotation, Docker test suites), `relay/`
(Rust workspace: `relayd` and its xray API client), `config-templates/` (CLI and third-party client configs).

A `SavedProfile` holds a relay, a WireGuard peer, or both.
`UserSettings.effectiveFor` drops the peer when the entry hop is off;
`renderPlatformTunnelConfig` picks a TUN chain, a bare WireGuard TUN, or (desktop,
**Route all apps** off) a loopback proxy; `TunnelController` runs it.

## Invariants

Check each before changing the area. Breaking one reintroduces a failure that is
invisible in testing.

**Structure**
- [ ] Dependencies flow `feature/* → core/domain, data, designsystem, ui`;
      `domain → data`; `data → datastore, tunnel, config`. No reverse imports.
- [ ] Platform behavior is `expect`/`actual` or a platform-suffixed Koin module,
      never a runtime `if (platform)`.
- [ ] Each module wires its own Koin module under `.../di/*Module.kt`.
- [ ] A bare WireGuard peer has no relay: nothing that needs the relay (WARP,
      probes) may assume one. Endpoints are told apart by tag, not by "a wireguard
      endpoint exists".

**WARP tail** (`SingBoxConfigFactory.addWarpEndpoint`, `RelayConfig.exemptIps`, `SiteLearner`, `UserSettings.warpMode`)
- [ ] The `warp-exit` endpoint always carries `detour` to the relay outbound.
- [ ] It is never in the kill switch's exemption list.
- [ ] Probe inbounds listen on loopback only, with per-config random port and credentials.
- [ ] The bare link is never probed, and a site goes there only on the user's
      "Send direct" tap, matched by exact host. The order is relay, WARP, entry hop
      (on the user's tap), bare link (on the user's tap).
- [ ] WARP keys and probe credentials are never logged or exported.

**Kill switch, desktop** (`cli/killswitch/vpn-chain-killswitch.c` header, `KillSwitchHelper.kt`)
- [ ] The helper takes a fixed argv, no shell, strictly validated arguments.
- [ ] Firewall rules, not routing-table changes (sing-box's interface detection reads the routes).
- [ ] `reject`, not `drop`; keep the `tun0` exemption.
- [ ] Torn down on Disconnect, not on a crash.
- [ ] The fail-open mark exists only on desktop, when the config renders a learned-direct rule-set (sites the user sent direct).

**Android resilience** (`VpnChainService`, `ChainProbe`, `AndroidWakeGuard`, `NetworkMonitor.linkChanges`, `ChainSupervisor.awaitUsableLink`)
- [ ] The service's lifetime is the user's intent, not the engine's: a drop keeps the foreground service.
- [ ] Connected means a real request through the TUN succeeded, not that libbox is healthy.
- [ ] `linkChanges` (link identity), not the boolean `online`, detects a Wi-Fi/cellular handover.
- [ ] A wake lock spans the dial and the health probe, and the supervisor waits for a usable link before dialling.
- [ ] An update restarts the chain when the connection intent is on.

**Secrets**
- [ ] Credentials never reach the repo, logs, clash API output or import strings. Fixtures use documentation addresses (`203.0.113.x`).
- [ ] WARP registration is the one request outside the chain (it must exist before the tunnel). Nothing else may use the bare link unasked.
- [ ] Android kill switch is the OS Always-on VPN: the app can detect it, never enable it.

**Docs**
- [ ] Read the KDoc and header comments in the files named above before changing that area.
- [ ] There is no docs directory: user docs go in the README, maintainer docs in
      CONTRIBUTING.md, this file, or a comment next to the code.
- [ ] Read [.impeccable.md](.impeccable.md) before touching `core/designsystem` or a feature's Compose UI.

## Traps

- Xray 26.10.21 rejects the sing-box 1.14.3 (and 1.13.14) REALITY client: the server forwards it to the decoy. The relay is pinned in `server/lib/pins.sh`; bump it only with `server/tests/matrix.sh` green.
- SSH to the VPS rides the chain. Restarting xray kills a session that is mid-operation, so run restarts detached and reconnect to check.
- xray users added through the HandlerService API (`adu`) live in memory only and vanish on restart unless also written to `config.json`.
- sing-box 1.14.3 fails its whole start when any inbound cannot bind. Probe and clash API ports are picked just before bind, so a start that fails that way is rendered once more with fresh ports.
- sing-box accepts one `experimental` block. Rendering the cache file and the clash API separately silently drops the first.
- The systemd relay outlives the app, including an in-app update. Bump `SingBoxConfigFactory.RENDER_SCHEMA` whenever a render would route differently, or an adopted relay keeps the older build's routing.
- Without the relay unit, a sing-box package upgrade drops its file capability and connect fails with `TUNSETIFF: operation not permitted`.
- A kill-switch helper built before `--allow-mark` exits 2 on it. The app re-arms without the mark, so sites sent direct stay blocked until the helper is rebuilt and `setcap` run again.
- The WARP tail is another 1280-byte WireGuard hop, so it caps the TUN MTU at 1280 even on a single-hop chain.
- Android's libbox 1.13.14 has the rule-set file watcher and the connection-close command, but neither has been exercised on a device. If the reload does not fire, a learned site applies at the next reconnect.
- The Android profile sits in app-private DataStore unencrypted; it is not behind the Keystore yet.
