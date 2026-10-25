package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import kotlinx.coroutines.flow.first

/** Makes another saved connection the active one, moving a wanted tunnel onto it. */
class SwitchProfileUseCase(
    private val profileRepository: ProfileRepository,
    private val chainRepository: ChainRepository,
) {
    suspend operator fun invoke(id: String): Result<Unit> {
        if (id == profileRepository.activeProfileId.first()) return Result.success(Unit)
        profileRepository.setActive(id)
        return chainRepository.restartIfWanted()
    }
}
