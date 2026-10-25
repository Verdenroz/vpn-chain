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
        val state = chainRepository.status.first().state
        // An attempt in flight, or one the supervisor is about to retry, dials
        // the old config, so anything the user wants up is restarted.
        val wantedUp = state == TunnelState.Connected || state == TunnelState.Connecting ||
            chainRepository.connectionIntent.first()
        profileRepository.setActive(id)
        if (!wantedUp) return Result.success(Unit)
        chainRepository.disconnect()
        return chainRepository.connect()
    }
}
