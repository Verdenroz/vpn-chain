# Android

The app is the device's only VPN and runs both hops itself. Android allows one
active `VpnService`, so a separate VPN app cannot carry the entry hop.

```
phone → sing-box VpnService (this app)
          → WireGuard endpoint → entry peer     (entry hop, optional)
            → VLESS+REALITY    → your VPS       (relay, exits from the VPS's address)
              → internet
```

## Set up

1. Install the APK from the
   [latest release](https://github.com/Verdenroz/vpn-chain/releases/latest)
   (`arm64-v8a` for phones, `x86_64` for emulators), or build one with
   `./gradlew :androidApp:assembleDebug`.
2. Import a connection: **Settings → Connection → Import**. Scan the desktop
   app's QR, or paste a `secrets.env` or a WireGuard `.conf`. Review the server
   shown, then **Use it**.
3. For the entry hop, use a WireGuard config made for this phone alone, so it
   can be revoked independently. Without one the VPS sees your mobile IP.
4. Tap **Connect** and accept the system VPN prompt once.
5. Turn on [Always-on VPN](#kill-switch).

Settings you may want under **Automation**: Connect on launch, Reconnect
automatically, Start on boot (needs the VPN permission granted already). The
Quick Settings tile and the two home-screen widgets (toggle, controls) start and
stop the chain.

## Two tiers

| Tier | Path | Trade |
|---|---|---|
| Full chain (recommended) | Entry peer, then relay | The VPS never sees your carrier address |
| Relay only | Straight to the relay | Simpler; the VPS sees your real mobile IP |

Switch the entry hop off without editing the profile at **Settings → Routing →
WireGuard entry hop**.

| Placeholder | Source |
|---|---|
| `VPS_IP`, `VLESS_UUID`, `REALITY_PUBKEY`, `SHORT_ID`, `SNI` | The relay identity; for a revocable phone, `server/devices.sh add` ([devices.md](devices.md)) |
| `ENTRY_PRIVKEY`, `ENTRY_ADDRESS`, `ENTRY_PEER_PUBKEY`, `ENTRY_HOST`, `ENTRY_PORT` | A dedicated WireGuard config for this device |
| `ENTRY_DNS` (optional) | The `.conf`'s `DNS =` line. Copy it to keep the provider's DNS filtering: that resolver is reachable only through the same peer. |

## Without building the app

`config-templates/sing-box-android.template.json` encodes the same chain: a
`wireguard` endpoint `entry-hop` and a VLESS outbound with `"detour":
"entry-hop"`. Fill the placeholders above and import it into **sing-box for
Android** (SFA) or **NekoBox**. For relay only, drop the endpoint and the `detour`.

## Kill switch

The app cannot enable a kill switch; Android keeps that a system choice.

1. **Settings → Network & Internet → VPN**.
2. Tap the gear next to vpn-chain.
3. Turn on **Always-on VPN** and **Block connections without VPN**.

With both on, a dropped tunnel blocks all traffic instead of falling back to
Wi-Fi or cellular.

The **Settings → Kill switch** card shows when the app has seen Always-on in
action: Android starts the service with a null `Intent` only for Always-on, the
one signal a normal app gets. It is best effort and stays "not armed" until
Always-on has launched the service once. The card links to the VPN settings.

## Staying connected

Five invariants keep a long session alive. Each fixes a failure that is invisible
in testing and obvious in a pocket. Code: `VpnChainService`, `ChainProbe`,
`AndroidWakeGuard`, `NetworkMonitor.linkChanges`, `ChainSupervisor`.

1. **The service outlives the engine.** Its lifetime is the user's connection
   intent. A drop calls `dropEngine` (reporting `TunnelState.Error`) and keeps
   the foreground notification. Only `ACTION_STOP`, `ACTION_STOP_BY_USER`,
   `onRevoke` or `ChainRepository.release()` end it. A service that stops itself
   leaves the process cached, Doze kills it first, and Android 12+ refuses the
   background foreground-service start that would revive it. A process already
   running a foreground service is exempt from that rule.
2. **Connected means it carries traffic.** The TUN exists within milliseconds,
   long before the entry hop has handshaked, and libbox's status stream is
   loopback, so it stays healthy through a dead tunnel. `ChainProbe` makes a
   real request through the TUN (the app's own sockets are not `protect()`ed, so
   they take the chain): once as a readiness gate, then every 45 seconds, on every
   underlying-link change, and on screen-on. The screen-on trigger covers the
   tick running off a clock Doze stops. Two independent probe targets mean one
   blocked host does not read as a dead chain; a 10-second debounce collapses
   triggers that land together.
3. **Handovers are a first-class signal.** `NetworkMonitor.linkChanges` reports
   the identity of the non-VPN default link changing. A boolean `online` stays
   `true` across Wi-Fi to cellular while every socket on the old link
   black-holes. A link coming back after an outage counts too, and the watchdog
   waits for `online` before probing it. It drives the watchdog and ends a
   reconnect backoff early.
4. **Doze does not suspend an attempt.** `AndroidWakeGuard` holds a partial wake
   lock across the dial and the health probe, so a probe that times out because
   the phone slept does not read as a dead chain. Backoff waits stay outside it:
   a network callback ends them and wakes the device itself. The supervisor waits
   for a usable link before dialling, because an attempt that cannot succeed
   still holds the lock until its readiness gate times out.
5. **An update brings the chain back.** Installing an update replaces the process
   and drops the tunnel. `PackageReplacedReceiver` (`MY_PACKAGE_REPLACED`)
   restarts it when the saved connection intent is on. That broadcast is exempt
   from background-start limits, and `specialUse` is not a foreground service
   type barred from such receivers.

## Where the code is

All in `core/tunnel/src/androidMain` unless noted.

| Piece | Role |
|---|---|
| `VpnChainService` | The `VpnService`. Runs sing-box in-process through libbox's `CommandServer`; `CommandClient` streams logs, status and connections |
| `VpnPlatformInterface` | libbox `PlatformInterface`: builds the TUN from `TunOptions`, `protect()`s the engine's sockets, reports the default network |
| `AndroidTunnelController`, `TunnelBridge` | The controller sends start and stop intents and mirrors the service's status and log flows through a process-wide bridge |
| `VpnPermissionCoordinator` | One-time `VpnService.prepare` consent, handled by `MainActivity` |
| `core/config` `androidChainConfig` | Emits the `tun` inbound, the `entry-hop` endpoint and the VLESS relay detoured through it |

The engine is the prebuilt `com.github.singbox-android:libbox` AAR (genuine
sing-box, tag equals sing-box version) from JitPack; see `settings.gradle.kts` and
`gradle/libs.versions.toml`. The APK splits by ABI because `libbox.so` is about
60 MB each. Configs are validated against `sing-box check` 1.13.14, the version
libbox embeds.

## Known limit

The profile persists in app-private DataStore, unencrypted. Moving the credential
fields behind the Android Keystore is not done.
