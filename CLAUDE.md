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
| `core/data`, `core/domain` | Repositories, use cases, `ChainSupervisor` |
| `core/designsystem`, `core/ui` | Theme, map, shared composables |
| `feature/{chain,settings,logs}` | Compose screens and ViewModels |
| `app-common` | Shell, nav, Koin aggregation |
| `androidApp`, `desktopApp` | Platform entry points |

Also `core/common`, `core/logging`, `core/geoip`. Full table:
[docs/architecture.md](docs/architecture.md). Apply a convention plugin from
`build-logic/convention/` to a new module instead of configuring Gradle by hand.

## Invariants

Check each before changing the area. Breaking one reintroduces a failure that is
invisible in testing.

**Structure** ([architecture](docs/architecture.md))
- [ ] Dependencies flow `feature/* → core/domain, data, designsystem, ui`;
      `domain → data`; `data → datastore, tunnel, config`. No reverse imports.
- [ ] Platform behavior is `expect`/`actual` or a platform-suffixed Koin module,
      never a runtime `if (platform)`.
- [ ] Each module wires its own Koin module under `.../di/*Module.kt`.
- [ ] A bare WireGuard peer has no relay: nothing that needs the relay (WARP,
      probes) may assume one. Endpoints are told apart by tag, not by "a wireguard
      endpoint exists".

**WARP tail** ([warp-exit](docs/warp-exit.md))
- [ ] The `warp-exit` endpoint always carries `detour` to the relay outbound.
- [ ] It is never in the kill switch's exemption list.
- [ ] Probe inbounds listen on loopback only, with per-config random port and credentials.
- [ ] The bare link is never probed, and a site goes there only on the user's
      "Send direct" tap, matched by exact host.
- [ ] WARP keys and probe credentials are never logged or exported.

**Kill switch, desktop** ([kill-switch](docs/kill-switch.md))
- [ ] The helper takes a fixed argv, no shell, strictly validated arguments.
- [ ] Firewall rules, not routing-table changes (sing-box's interface detection reads the routes).
- [ ] `reject`, not `drop`; keep the `tun0` exemption.
- [ ] Torn down on Disconnect, not on a crash.
- [ ] The fail-open mark exists only on desktop, in `automatic` mode, with no entry hop.

**Android resilience** ([android](docs/android.md#staying-connected))
- [ ] The service's lifetime is the user's intent, not the engine's: a drop keeps the foreground service.
- [ ] Connected means a real request through the TUN succeeded, not that libbox is healthy.
- [ ] `linkChanges` (link identity), not the boolean `online`, detects a Wi-Fi/cellular handover.
- [ ] A wake lock spans the dial and the health probe, and the supervisor waits for a usable link before dialling.
- [ ] An update restarts the chain when the connection intent is on.

**Secrets**
- [ ] Credentials never reach the repo, logs, clash API output or import strings. Fixtures use documentation addresses (`203.0.113.x`).
- [ ] Android kill switch is the OS Always-on VPN: the app can detect it, never enable it.

**Docs**
- [ ] Read the linked doc before changing WARP, the kill switch, or Android resilience.
- [ ] Read [.impeccable.md](.impeccable.md) before touching `core/designsystem` or a feature's Compose UI.

## Traps

- Xray 26.10.21 rejects the sing-box 1.14.3 (and 1.13.14) REALITY client: the server forwards it to the decoy. The relay is pinned in `server/lib/pins.sh`; bump it only with `server/tests/matrix.sh` green.
- SSH to the VPS rides the chain. Restarting xray kills a session that is mid-operation, so run restarts detached and reconnect to check.
