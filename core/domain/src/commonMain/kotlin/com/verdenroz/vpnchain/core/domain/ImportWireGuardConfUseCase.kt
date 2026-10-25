package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.WireGuardEntry

/**
 * Imports a WireGuard `.conf` as a new connection, or as the active chain's
 * entry hop. Either changes what the active connection dials, so a wanted
 * tunnel is restarted onto it.
 */
class ImportWireGuardConfUseCase(
    private val profileRepository: ProfileRepository,
    private val chainRepository: ChainRepository,
) {
    suspend operator fun invoke(confText: String, intoActive: Boolean = false): Result<WireGuardEntry> =
        profileRepository.importWireGuardConf(confText, intoActive).mapCatching { entry ->
            chainRepository.restartIfWanted().getOrThrow()
            entry
        }
}
