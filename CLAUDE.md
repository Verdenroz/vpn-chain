# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A layered, censorship-resistant VPN chain (`you → WireGuard entry hop →
VLESS+REALITY relay VPS → internet`) built two ways: a bash CLI
(`cli/vpn-chain`) and a Kotlin Multiplatform GUI (Android + desktop) that
shares one core. Real credentials never live in the repo — see the README's
"Secrets" section for the `secrets.env` flow before touching anything that
reads config.

## Commands

Gradle must **run** on JDK 17–21 (not 22+); `gradle.properties` pins
`org.gradle.java.home` to a local JDK 21 install, so if a build fails with a
JDK version error first check that path is still valid on your machine.

```
./gradlew :desktopApp:run                    # run the desktop app
./gradlew :androidApp:assembleDebug           # build the Android debug APK
./gradlew test                                # all commonTest/desktopTest suites
./gradlew :core:config:test                   # single module's tests
./gradlew :core:config:desktopTest --tests "*.SingBoxConfigFactoryTest"   # single test class
./gradlew :desktopApp:installDesktopEntry     # install a wofi/rofi launcher entry + icons
```

There is no Android instrumented/emulator test suite in the repo — module
tests run on the `desktopTest` JVM source set (both KMP targets are JVM, so
`commonTest` runs unchanged there without a device).

Release builds (`packageReleaseDeb`, `packageReleaseMsi`, `packageReleaseDmg`,
`assembleRelease`) are driven by `.github/workflows/release.yml` on a `v*` tag
push; see the README's "Releases" section. The single version source is
`vpnchain.version` in `gradle.properties`.

## Architecture

Kotlin Multiplatform monorepo following [Now in Android](https://github.com/android/nowinandroid)'s
`core` / `feature` / app layering, adapted with **Koin** for DI instead of
Hilt (so `commonMain` code works unmodified on both Android and desktop —
no codegen dependent on an Android context).

**Layering rule**: dependencies flow one way — `feature/*` depends on
`core/domain`, `core/data`, `core/designsystem`, `core/ui`; `core/domain`
depends on `core/data`'s repository interfaces; `core/data` depends on
`core/datastore`/`core/tunnel`/`core/config`. Each module wires its own Koin
module under `.../di/*Module.kt`, aggregated in
`app-common/.../app/di/AppModules.kt`. When a module needs
platform-specific behavior, it's expressed as `expect`/`actual` (or a
platform-suffixed Koin module, e.g. `TunnelModule.android.kt` /
`TunnelModule.desktop.kt`) rather than a runtime `if (platform)` branch.

**Module map** (see the README for the full table): `core/model` (data
classes) → `core/config` (`SingBoxConfigFactory`: profile → sing-box JSON,
`renderPlatformTunnelConfig` branches per platform) → `core/datastore`
(profile/settings persistence) → `core/tunnel` (`TunnelController`: Android =
`VpnChainService` wrapping libbox's `CommandServer`, desktop = spawns the
`sing-box` binary) → `core/data` (repositories) → `core/domain` (use cases) →
`feature/{chain,settings,logs}` (Compose UI + ViewModels) → `app-common`
(themed shell, bottom-nav `NavHost`, Koin graph aggregation) →
`androidApp`/`desktopApp` (thin platform entry points).

**Convention plugins** (`build-logic/convention/`) centralize per-module
Gradle config instead of repeating it: `vpnchain.kmp.library` (KMP + Android
library, compileSdk 36/minSdk 26, JVM 17), `vpnchain.kmp.feature` (adds
Compose + the standard feature deps: model/domain/designsystem/ui, ViewModel,
navigation, Koin), `vpnchain.compose.multiplatform`, `vpnchain.android.application`.
Apply the closest-matching one to a new module rather than configuring
Kotlin/Android/Compose by hand.

**Desktop vs. Android tunnel divergence** (`core/tunnel`): Desktop runs the
external `sing-box` binary as a subprocess and coordinates with the CLI via a
shared pidfile (`$XDG_RUNTIME_DIR/vpn-chain/relay.pid`) so the GUI can adopt a
relay the CLI already started. Android runs sing-box in-process via the
prebuilt `libbox` AAR inside a `VpnService` (`VpnChainService` +
`VpnPlatformInterface` implementing libbox's `PlatformInterface`) — see
`docs/android.md` for the full wiring, and note Android's one-VPN-at-a-time
constraint means the app performs *both* chain hops itself instead of
delegating the entry hop to a separate VPN app.

**Kill switch** has two independent implementations depending on platform and
mode — Android relies on OS-level Always-on VPN (no app code can enable it,
only detect it), desktop's WireGuard-entry-hop mode uses a narrow setuid-free
C helper (`cli/killswitch/vpn-chain-killswitch`) that installs an nftables
OUTPUT-reject table via a fixed argv (no shell). Read
**[docs/kill-switch.md](docs/kill-switch.md)** in full before modifying
either — the nftables rule design (reject vs. drop, the `tun0` exemption, why
it doesn't touch the routing table) encodes several non-obvious constraints
from sing-box's own interface auto-detection.

**Design system**: `.impeccable.md` at the repo root captures the UI's brand
direction (a "front-panel instrument" aesthetic — lamps not badges, engraved
labels, machined depth, dark-first) for the `impeccable`/`frontend-design`
skills. Read it before touching `core/designsystem` or any feature's Compose
UI.

## Package/namespace

All Kotlin source lives under `com.verdenroz.vpnchain.<layer>.<module>`
(e.g. `com.verdenroz.vpnchain.core.tunnel`, `com.verdenroz.vpnchain.feature.chain`).
