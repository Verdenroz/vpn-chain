package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.flow.first

/**
 * Makes another saved connection the active one. A live tunnel is still
 * running the old config, so switching takes it down and brings it back up
 * rather than reconfiguring in place.
 */
class SwitchProfileUseCase(
    private val profileRepository: ProfileRepository,
    private val chainRepository: ChainRepository,
) {
    /** @return the reconnect result, or success when there was no tunnel to move. */
    suspend operator fun invoke(id: String): Result<Unit> {
        if (id == profileRepository.activeProfileId.first()) return Result.success(Unit)
        val wasConnected = chainRepository.status.first().state == TunnelState.Connected
        profileRepository.setActive(id)
        if (!wasConnected) return Result.success(Unit)
        chainRepository.disconnect()
        return chainRepository.connect()
    }
}
