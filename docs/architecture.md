# Architecture

vpn-chain is a Kotlin Multiplatform monorepo (Android and desktop JVM) plus a
bash CLI and server scripts. It follows
[Now in Android](https://github.com/android/nowinandroid)'s `core` / `feature` /
app split, with Koin for DI instead of Hilt so `commonMain` code runs unchanged
on both platforms.

## Modules

| Module | Holds |
|---|---|
| `core/model` | Data classes: `SavedProfile`, `ChainProfile`, `ChainStatus`, `TunnelState`, `UserSettings`, `LearnedSite`, `LogEntry` |
| `core/common` | App coroutine scope and dispatcher qualifiers, `Platform`, system tray, login autostart |
| `core/logging` | `Logger` facade (Logcat on Android, stderr on desktop) |
| `core/config` | `SingBoxConfigFactory` (profile to sing-box JSON), `SecretsEnvParser`, `WireGuardConfParser`, learned-site rule-sets |
| `core/datastore` | DataStore persistence for profiles and settings |
| `core/tunnel` | `TunnelController`: `VpnChainService` on Android, the `sing-box` process on desktop; log and status flows |
| `core/geoip` | Country lookup for each hop, from a bundled range table, for the map |
| `core/warp` | Cloudflare WARP registration, site probing and classification |
| `core/update` | In-app updater: release manifest, verified download, installers |
| `core/data` | Repositories: chain, profile, settings, logs, WARP, learned sites |
| `core/domain` | Use cases (connect, switch profile, import) and `ChainSupervisor` |
| `core/designsystem` | Theme, colors, type, the route map, status bar, shared components |
| `core/ui` | Shared composables and theme resolution |
| `feature/chain` | Chain screen: map, status, hop rows, connect, blocked-site prompt |
| `feature/sites` | Learned sites by the way they leave, with the action for each |
| `feature/settings` | Profiles, import, routing, kill switch, DNS, appearance, About |
| `feature/logs` | Live sing-box log viewer |
| `app-common` | Themed shell (status bar, bottom tabs or nav rail, `NavHost`), update banner, Koin aggregation |
| `androidApp`, `desktopApp` | Platform entry points |

Outside Gradle:

| Path | What |
|---|---|
| `cli/vpn-chain` | CLI: renders a sing-box config from `secrets.env`, starts the entry app and the relay |
| `cli/*.sh`, `cli/systemd`, `cli/polkit` | Desktop install, one-time setup, relay unit, polkit rules, import and diagnostics |
| `cli/killswitch/` | C helper that installs the nftables kill switch |
| `server/` | VPS provisioning and key rotation |
| `config-templates/` | sing-box templates for the CLI and for off-the-shelf Android clients |

## Layering

Dependencies flow one way:

```
feature/* -> core/domain, core/data, core/designsystem, core/ui
core/domain -> core/data (interfaces)
core/data -> core/datastore, core/tunnel, core/config
```

Each module wires its own Koin module under `.../di/*Module.kt`.
`app-common/.../app/di/AppModules.kt` aggregates them.

Platform differences use `expect`/`actual`, or a platform-suffixed Koin module
(`TunnelModule.android.kt`, `TunnelModule.desktop.kt`), never a runtime
`if (platform)` branch.

New modules apply the closest convention plugin from `build-logic/convention/`
instead of configuring Kotlin, Android or Compose by hand:

| Plugin | For |
|---|---|
| `vpnchain.kmp.library` | KMP and Android library (compileSdk 36, minSdk 26, JVM 17) |
| `vpnchain.kmp.feature` | Adds Compose and the standard feature dependencies |
| `vpnchain.compose.multiplatform` | Compose Multiplatform |
| `vpnchain.android.application` | The Android app; derives the version code from `vpnchain.version` |

## From profile to tunnel

1. A **saved connection** (`SavedProfile`) holds a relay (`ChainProfile`), a
   WireGuard peer, or both. Both is the full chain; WireGuard alone is a plain
   tunnel to that peer.
2. `UserSettings.effectiveFor` drops the WireGuard hop when the entry hop is
   switched off.
3. `SingBoxConfigFactory` renders the sing-box JSON.
   `renderPlatformTunnelConfig` picks the shape: a TUN chain, a bare WireGuard
   TUN, or (desktop only, system-wide TUN off) a local SOCKS/HTTP proxy.
4. `TunnelController` runs it. `ChainSupervisor` reconnects after a drop.

Both hops are WireGuard endpoints, so the factory tells the entry hop from the
WARP tail by endpoint tag, not by type.

## Desktop and Android tunnels

| | Desktop | Android |
|---|---|---|
| Engine | The `sing-box` binary: a systemd unit when installed, otherwise a child process | libbox, in-process, inside a `VpnService` |
| Entry hop | A sing-box WireGuard endpoint, or an external VPN app's interface (`proton0`) | Always a sing-box endpoint: Android allows one VPN at a time |
| Kill switch | nftables helper (`cli/killswitch/`) | System Always-on VPN |
| CLI link | Shared pidfile at `$XDG_RUNTIME_DIR/vpn-chain/relay.pid`; the GUI adopts a relay the CLI started | None |

More: [desktop-tun.md](desktop-tun.md), [android.md](android.md),
[kill-switch.md](kill-switch.md).

## Tests

`./gradlew test` runs every module's `commonTest` and `desktopTest`. Both KMP
targets are JVM, so common tests run without a device. There is no instrumented
Android suite. A single module or class:

```
./gradlew :core:config:test
./gradlew :core:config:desktopTest --tests "*.SingBoxConfigFactoryTest"
```
