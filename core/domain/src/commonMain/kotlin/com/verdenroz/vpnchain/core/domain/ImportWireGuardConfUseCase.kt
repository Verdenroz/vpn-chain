package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.WireGuardEntry

/** Imports a WireGuard `.conf` as a new connection, or as the active chain's entry hop. */
class ImportWireGuardConfUseCase(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(confText: String, intoActive: Boolean = false): Result<WireGuardEntry> =
        profileRepository.importWireGuardConf(confText, intoActive)
}
