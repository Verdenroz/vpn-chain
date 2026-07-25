package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.renderPlatformTunnelConfig
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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

    /** Whether this platform has an OS-level kill switch worth guiding the user toward. */
    val killSwitchGuidanceSupported: Boolean

    /** True once we've observed the OS itself launch us as an always-on VPN (Android only). */
    val alwaysOnDetected: Flow<Boolean>

    /** Opens the platform's VPN settings screen, if [killSwitchGuidanceSupported]. */
    fun openSystemVpnSettings()
}

internal class DefaultChainRepository(
    private val controller: TunnelController,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val preferences: VpnChainPreferencesDataSource,
    private val logger: Logger,
) : ChainRepository {

    override val status: Flow<ChainStatus> = controller.status
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
        val profile: ChainProfile = profileRepository.profile.first()
            ?: return Result.failure(IllegalStateException("No chain profile configured"))
        val settings = settingsRepository.settings.first()

        return runCatching {
            val configJson = renderPlatformTunnelConfig(profile, settings.systemWideTun)
            controller.start(configJson, settings.killSwitchEnabled)
        }.onFailure { logger.e(TAG, "connect failed", it) }
    }

    override suspend fun disconnect() {
        preferences.setConnectionIntent(false)
        runCatching { controller.stop() }
            .onFailure { logger.e(TAG, "disconnect failed", it) }
    }

    private companion object {
        const val TAG = "ChainRepository"
    }
}
