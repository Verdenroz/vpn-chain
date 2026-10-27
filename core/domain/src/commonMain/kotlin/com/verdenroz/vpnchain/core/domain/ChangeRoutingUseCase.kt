package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlinx.coroutines.flow.first

/**
 * Changes a setting that shapes the rendered config: where sites leave the chain,
 * or how much sing-box logs. A running tunnel keeps the config it started with,
 * while the screens read exits from the current settings, so a wanted tunnel is
 * restarted onto the change.
 */
class ChangeRoutingUseCase(
    private val settingsRepository: SettingsRepository,
    private val chainRepository: ChainRepository,
) {
    suspend fun setEntryHopEnabled(enabled: Boolean): Result<Unit> =
        change({ it.entryHopEnabled == enabled }) { settingsRepository.setEntryHopEnabled(enabled) }

    suspend fun setWarpMode(mode: WarpMode): Result<Unit> =
        change({ it.warpMode == mode }) { settingsRepository.setWarpMode(mode) }

    suspend fun setFailOpen(enabled: Boolean): Result<Unit> =
        change({ it.failOpen == enabled }) { settingsRepository.setFailOpen(enabled) }

    suspend fun setConnectionLog(enabled: Boolean): Result<Unit> =
        change({ it.connectionLog == enabled }) { settingsRepository.setConnectionLog(enabled) }

    private suspend fun change(unchanged: (UserSettings) -> Boolean, write: suspend () -> Unit): Result<Unit> {
        if (unchanged(settingsRepository.settings.first())) return Result.success(Unit)
        write()
        return chainRepository.restartIfWanted()
    }
}
