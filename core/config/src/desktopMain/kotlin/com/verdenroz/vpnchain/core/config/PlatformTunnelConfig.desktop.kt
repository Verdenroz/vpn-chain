package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.common.PrivateStorage
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpExit
import java.io.File

/**
 * Desktop renders a clash API into every config so the controller has a way to
 * read traffic counters — sing-box is an opaque subprocess here, unlike Android
 * where libbox reports status in-process.
 */
actual fun renderPlatformTunnelConfig(
    profile: SavedProfile,
    settings: UserSettings,
    warp: WarpExit?,
    learned: LearnedRuleSetStore?,
): String {
    val relay = profile.relay
    val clashApi = newClashApi()
    // The unit runs sing-box as its own user, which can reach neither
    // this user's home nor its stdout.
    val cachePath = if (SystemdRelay.available) SystemdRelay.cachePath else cacheFilePath()
    val logPath = if (SystemdRelay.available) SystemdRelay.logFile.absolutePath else null
    val probe = relay?.let { newProbeProxy() }
    // No relay means no SOCKS-proxy shape to fall back on: a bare WireGuard
    // peer only exists as a TUN.
    return if (relay == null) {
        SingBoxConfigFactory.wireGuardConfig(
            entry = requireNotNull(profile.wireGuard),
            clashApi = clashApi,
            dnsFilter = settings.dnsFilter,
            cachePath = cachePath,
            allowLocalNetworks = settings.allowLocalNetworks,
            logPath = logPath,
            connectionLog = settings.connectionLog,
        )
    } else if (settings.systemWideTun) {
        SingBoxConfigFactory.androidChainConfig(
            profile = relay,
            entry = profile.wireGuard,
            clashApi = clashApi,
            dnsFilter = settings.dnsFilter,
            cachePath = cachePath,
            warp = warp,
            warpMode = settings.warpMode,
            allowLocalNetworks = settings.allowLocalNetworks,
            logPath = logPath,
            probe = probe,
            learnedWarpPath = learned?.path(RuleSetKind.Warp),
            learnedOpenPath = learned?.path(RuleSetKind.Open),
            learnedDirectPath = learned?.path(RuleSetKind.Direct),
            failOpen = settings.failOpen,
            connectionLog = settings.connectionLog,
            markDirect = true,
        )
    } else {
        SingBoxConfigFactory.mixedProxyConfig(
            profile = relay,
            clashApi = clashApi,
            warp = warp,
            warpMode = settings.warpMode,
            probe = probe,
            learnedWarpPath = learned?.path(RuleSetKind.Warp),
            learnedDirectPath = learned?.path(RuleSetKind.Direct),
            failOpen = settings.failOpen,
            connectionLog = settings.connectionLog,
        )
    }
}

/**
 * Beside the rest of our state rather than in sing-box's working directory,
 * which is whatever the app was launched with — `/` for a login item.
 */
private fun cacheFilePath(): String = File(PrivateStorage.configDir, "singbox-cache.db").absolutePath

/** A fresh port and secret per rendered config; the relay itself does not depend on the API. */
private fun newClashApi(): ClashApi = ClashApi(port = freeLoopbackPort(), secret = randomHexToken(SECRET_BYTES))

private const val SECRET_BYTES = 24
