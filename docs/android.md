# Android

## The one-VPN constraint (important)

Android's `VpnService` allows **only one active VPN at a time**. You therefore
**cannot** run the ProtonVPN app *and* a separate relay app chained together —
the way the desktop can with the SOCKS-proxy mode.

So on Android the app **is** the single VPN, and sing-box performs *both* hops
itself: the Proton entry hop as a WireGuard endpoint, then the VLESS relay on
top. The ProtonVPN app is not part of the chain here.

```
phone
  → sing-box VpnService (this app)
       → WireGuard endpoint → ProtonVPN     (entry hop)
          → VLESS+REALITY   → your VPS      (relay, exits from the VPS's own IP)
             → internet
```

## Config

`config-templates/sing-box-android.template.json` encodes exactly this: a
`wireguard` **endpoint** (`proton-entry`) that the VLESS outbound reaches via
`"detour": "proton-entry"`. Fill the placeholders:

| Placeholder | Source |
|-------------|--------|
| `VPS_IP`, `VLESS_UUID`, `REALITY_PUBKEY`, `SHORT_ID`, `SNI` | same relay identity as the desktop chain (`~/.config/vpn-chain/secrets.env`) |
| `PROTON_ENTRY_*` (private key, address, peer pubkey, endpoint host/port) | a **dedicated** Proton WireGuard config for this device — generate a *separate* one at account.protonvpn.com so it can be revoked independently |
| `PROTON_ENTRY_DNS` (optional) | the `.conf`'s `DNS =` line — only present if you generated the config with NetShield on; copying it over is what keeps NetShield's ad/malware/tracker filtering working, since it's Proton's own resolver and only reachable through this same peer |

## Two tiers

- **Full chain (recommended):** the template above. Entry hop through Proton, so
  your mobile carrier IP is hidden from the VPS.
- **Relay only:** drop the `proton-entry` endpoint and the `detour`, pointing the
  VLESS outbound straight out. Simpler, but the VPS sees your real mobile IP.

This same choice is available on desktop when system-wide TUN mode is on — see
the root `README.md`'s "System-wide routing on desktop (TUN)" section.

## Kill switch

There's no code-level kill switch the app can enable for you — Android
deliberately keeps this a system Settings decision, the same category as the
VPN consent prompt itself. The real one:

**Settings → Network & Internet → VPN → (gear icon next to vpn-chain) → enable
"Always-on VPN" and "Block connections without VPN".** With both on, if the
tunnel ever drops unexpectedly, the OS blocks all network traffic instead of
falling back to your carrier/Wi-Fi unencrypted.

The app's Settings screen has a "Kill switch" card that shows whether it's
detected this — `VpnChainService.onStartCommand` gets called with a null
`Intent` only when Always-on triggers it, which is the one reliable signal
available to a normal app. If it hasn't detected that yet, the card links
straight to the VPN settings screen so you can turn it on. This detection is
best-effort: it only fires the first time Always-on actually launches the
service, not proactively before you've ever triggered it.

## Native app wiring (this repo)

The Android tunnel is **implemented** and the app builds a runnable APK. The
pieces, all in `core/tunnel/src/androidMain`:

- **`VpnChainService`** — the single `VpnService`. It runs sing-box in-process
  through libbox's command server: `CommandServer.startOrReloadService(config)`
  boots the engine, which calls back into `openTun` to establish the TUN. A local
  `CommandClient` (subscribed to `CommandLog`) streams engine output to the Logs
  screen; `status` is driven from the service lifecycle.
- **`VpnPlatformInterface`** — implements libbox's `PlatformInterface`: builds the
  Android TUN from the engine's `TunOptions`, `protect()`s the box's own sockets
  so they skip the tunnel, and reports the default network for
  `auto_detect_interface` via a `ConnectivityManager` callback.
- **`AndroidTunnelController` + `TunnelBridge`** — the controller (in the UI
  process) sends start/stop intents and mirrors the service's status/log flows
  through the process-wide `TunnelBridge`.
- **`VpnPermissionCoordinator`** — `MainActivity` binds a launcher that handles
  the one-time `VpnService.prepare` consent; the controller awaits it before
  starting.

The core comes from the prebuilt `com.github.singbox-android:libbox` AAR (genuine
sing-box, tag == sing-box version) via JitPack — see the repo root
`settings.gradle.kts` and `gradle/libs.versions.toml`. The APK uses ABI splits
(`arm64-v8a` for phones, `x86_64` for emulators) because `libbox.so` is ~60 MB
per ABI.

**Config:** `renderPlatformTunnelConfig` (in `core/config`) picks the shape per
platform — Android calls `SingBoxConfigFactory.androidChainConfig`, which emits
the `tun` inbound, the `wireguard` `proton-entry` endpoint, and the VLESS relay
reached via that detour (mirrors `sing-box-android.template.json`). Supply the
`PROTON_ENTRY_*` fields (secrets.env or the Settings form) to enable the entry
hop; omit them for a relay-only chain. All shapes are validated against
`sing-box check` 1.13.14 (the version libbox embeds).

**Security to harden:** the `ChainProfile` persists in app-private DataStore
(`core/datastore`). Move the credential fields behind the Android Keystore / an
encrypted DataStore before any real distribution — never ship them in the APK.

## Import into an off-the-shelf client (no build)

For a quick start without building the app, import
`sing-box-android.template.json` (placeholders filled) into **sing-box for
Android** (SFA) or **NekoBox**. Same chain, no Kotlin required.
