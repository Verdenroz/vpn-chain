package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.common.PrivateStorage
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpExit
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Desktop renders a clash API into every config so the controller has a way to
 * read traffic counters — sing-box is an opaque subprocess here, unlike Android
 * where libbox reports status in-process.
 */
actual fun renderPlatformTunnelConfig(
    profile: SavedProfile,
    settings: UserSettings,
    warp: WarpExit?,
): String {
    val relay = profile.relay
    val clashApi = newClashApi()
    // No relay means no SOCKS-proxy shape to fall back on: a bare WireGuard
    // peer only exists as a TUN.
    return if (relay == null) {
        SingBoxConfigFactory.wireGuardConfig(
            entry = requireNotNull(profile.wireGuard),
            clashApi = clashApi,
            dnsFilter = settings.dnsFilter,
            cachePath = if (SystemdRelay.available) SystemdRelay.cachePath else cacheFilePath(),
            allowLocalNetworks = settings.allowLocalNetworks,
            logPath = if (SystemdRelay.available) SystemdRelay.logFile.absolutePath else null,
        )
    } else if (settings.systemWideTun) {
        SingBoxConfigFactory.androidChainConfig(
            profile = relay,
            entry = profile.wireGuard,
            clashApi = clashApi,
            dnsFilter = settings.dnsFilter,
            // The unit runs sing-box as its own user, which can reach neither
            // this user's home nor its stdout.
            cachePath = if (SystemdRelay.available) SystemdRelay.cachePath else cacheFilePath(),
            warp = warp,
            warpMode = settings.warpMode,
            warpDomains = settings.warpDomains,
            allowLocalNetworks = settings.allowLocalNetworks,
            logPath = if (SystemdRelay.available) SystemdRelay.logFile.absolutePath else null,
        )
    } else {
        SingBoxConfigFactory.mixedProxyConfig(
            profile = relay,
            clashApi = clashApi,
            warp = warp,
            warpMode = settings.warpMode,
            warpDomains = settings.warpDomains,
        )
    }
}

/**
 * Beside the rest of our state rather than in sing-box's working directory,
 * which is whatever the app was launched with — `/` for a login item.
 */
private fun cacheFilePath(): String = File(PrivateStorage.configDir, "singbox-cache.db").absolutePath

/**
 * A fresh port and secret per rendered config. There is a window between
 * releasing the probe socket and sing-box binding it, but losing that race
 * costs stats, not the tunnel — the relay itself does not depend on the API.
 */
private fun newClashApi(): ClashApi = ClashApi(
    port = ServerSocket(0).use { it.localPort },
    secret = ByteArray(SECRET_BYTES)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) },
)

private const val SECRET_BYTES = 24
