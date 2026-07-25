package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.ChainProfile

/** Imports a profile from the CLI's `secrets.env` text and persists it. */
class ImportProfileUseCase(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(secretsEnvText: String): Result<ChainProfile> =
        profileRepository.importFromSecretsEnv(secretsEnvText)
}
