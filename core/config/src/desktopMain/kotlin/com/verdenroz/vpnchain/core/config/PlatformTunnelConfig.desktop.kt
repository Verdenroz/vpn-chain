package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Desktop renders a clash API into every config so the controller has a way to
 * read traffic counters — sing-box is an opaque subprocess here, unlike Android
 * where libbox reports status in-process.
 */
actual fun renderPlatformTunnelConfig(profile: ChainProfile, settings: UserSettings): String {
    val clashApi = newClashApi()
    return if (settings.systemWideTun) {
        SingBoxConfigFactory.androidChainConfig(
            profile = profile,
            clashApi = clashApi,
            dnsFilter = settings.dnsFilter,
            cachePath = cacheFilePath(),
        )
    } else {
        SingBoxConfigFactory.mixedProxyConfig(profile, clashApi)
    }
}

/**
 * Beside the rest of our state rather than in sing-box's working directory,
 * which is whatever the app was launched with — `/` for a login item.
 */
private fun cacheFilePath(): String {
    val dir = File(System.getProperty("user.home"), ".config/vpn-chain").apply { mkdirs() }
    return File(dir, "singbox-cache.db").absolutePath
}

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
