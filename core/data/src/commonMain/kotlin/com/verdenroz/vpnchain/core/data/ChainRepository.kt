package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.renderPlatformTunnelConfig
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.effectiveFor
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the tunnel from the stored [ChainProfile]. This is the seam the UI
 * talks to; it renders the sing-box config and hands it to the controller so
 * features never touch config JSON or credentials directly.
 */
interface ChainRepository {
    val status: Flow<ChainStatus>
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()

    /**
     * Whether the user last asked to be connected. Distinct from [status]: a
     * dropped tunnel is "wanted but down", which is what tells a reconnect it
     * should retry rather than accept the disconnect.
     */
    val connectionIntent: Flow<Boolean>

    /**
     * Reconnect attempt driven by the supervisor rather than the user. Skips
     * recording intent, so a failed retry can never be mistaken for the user
     * asking to connect.
     */
    suspend fun reconnect(): Result<Unit>

    /** Traffic and uptime for the live session. */
    val stats: Flow<SessionStats>

    /** Whether this platform has an OS-level kill switch worth guiding the user toward. */
    val killSwitchGuidanceSupported: Boolean

    /** True once we've observed the OS itself launch us as an always-on VPN (Android only). */
    val alwaysOnDetected: Flow<Boolean>

    /** Opens the platform's VPN settings screen, if [killSwitchGuidanceSupported]. */
    fun openSystemVpnSettings()

    /**
     * Hand the platform tunnel back without touching intent.
     *
     * For a drop nothing is going to retry. Android keeps its VpnService in the
     * foreground across a drop precisely so a reconnect can happen, so something
     * has to let go when no reconnect is coming — otherwise the user is left
     * with a notification promising one forever.
     */
    suspend fun release()
}

internal class DefaultChainRepository(
    private val controller: TunnelController,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val warpRepository: WarpRepository,
    private val preferences: VpnChainPreferencesDataSource,
    private val logger: Logger,
    scope: CoroutineScope,
) : ChainRepository {

    init {
        // A stop from Android's notification key or an OS revoke never reaches
        // disconnect(), so clear intent here or it reads as a drop to reconnect.
        scope.launch {
            controller.userStops.collect { preferences.setConnectionIntent(false) }
        }
        controller.installConfigProvider {
            preferences.setConnectionIntent(true)
            renderConfig()
        }
    }

    override val status: Flow<ChainStatus> = controller.status
    override val stats: Flow<SessionStats> = controller.stats
    override val killSwitchGuidanceSupported: Boolean = controller.killSwitchGuidanceSupported
    override val alwaysOnDetected: Flow<Boolean> = controller.alwaysOnDetected
    override val connectionIntent: Flow<Boolean> = preferences.connectionIntent
    override fun openSystemVpnSettings() = controller.openSystemVpnSettings()

    override suspend fun connect(): Result<Unit> {
        // Record intent before starting: a connect that fails still means the
        // user wants to be up, which is exactly when reconnect should engage.
        preferences.setConnectionIntent(true)
        return start()
    }

    override suspend fun reconnect(): Result<Unit> = start()

    private suspend fun start(): Result<Unit> {
        val settings = settingsRepository.settings.first()
        val configJson = renderConfig()
            ?: return Result.failure(IllegalStateException("No chain profile configured"))
        return runCatching { controller.start(configJson, settings.killSwitchEnabled) }
            .onFailure { logger.e(TAG, "connect failed", it) }
    }

    /** @return null when there is nothing to dial, or the render itself failed. */
    private suspend fun renderConfig(): String? {
        val profile: ChainProfile = profileRepository.profile.first() ?: return null
        val settings = settingsRepository.settings.first()
        // Registration talks to Cloudflare over the untunnelled link, so it
        // happens here rather than at render time — and a failure costs the
        // tail, never the connect.
        val warp = if (settings.warpMode == WarpMode.Off) null else warpRepository.exit()
        return runCatching { renderPlatformTunnelConfig(profile.effectiveFor(settings), settings, warp) }
            .onFailure { logger.e(TAG, "config render failed", it) }
            .getOrNull()
    }

    override suspend fun disconnect() {
        preferences.setConnectionIntent(false)
        runCatching { controller.stop() }
            .onFailure { logger.e(TAG, "disconnect failed", it) }
    }

    override suspend fun release() {
        runCatching { controller.release() }
            .onFailure { logger.e(TAG, "release failed", it) }
    }

    private companion object {
        const val TAG = "ChainRepository"
    }
}
