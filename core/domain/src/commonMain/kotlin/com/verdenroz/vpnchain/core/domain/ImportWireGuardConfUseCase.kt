package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.WireGuardEntry

/**
 * Imports a WireGuard `.conf`. A pasted or scanned conf names a server the user
 * may not have meant to trust, so [invoke] is only for after they have seen its
 * endpoint and chosen to use it: it switches the active connection (or its
 * entry hop) and restarts a wanted tunnel onto it. [save] only keeps it.
 */
class ImportWireGuardConfUseCase(
    private val profileRepository: ProfileRepository,
    private val chainRepository: ChainRepository,
) {
    /** A failed [reconnect] leaves the import saved, so it must not read as a failed import. */
    data class Imported(val entry: WireGuardEntry, val reconnect: Result<Unit>)

    suspend operator fun invoke(confText: String, intoActive: Boolean = false): Result<Imported> =
        profileRepository.importWireGuardConf(confText, intoActive).map { entry ->
            Imported(entry, chainRepository.restartIfWanted())
        }

    /** Adds it as another saved connection; the active one and its tunnel are left alone. */
    suspend fun save(confText: String): Result<WireGuardEntry> =
        profileRepository.importWireGuardConf(confText, intoActive = false, activate = false)
}
