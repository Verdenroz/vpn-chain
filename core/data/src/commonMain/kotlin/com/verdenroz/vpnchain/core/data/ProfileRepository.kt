package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.SecretsEnvParser
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.ChainProfile
import kotlinx.coroutines.flow.Flow

/** Reads and persists the chain identity ([ChainProfile]). */
interface ProfileRepository {
    val profile: Flow<ChainProfile?>
    suspend fun save(profile: ChainProfile)
    suspend fun clear()
    suspend fun importFromSecretsEnv(text: String): Result<ChainProfile>
}

internal class DefaultProfileRepository(
    private val preferences: VpnChainPreferencesDataSource,
) : ProfileRepository {

    override val profile: Flow<ChainProfile?> = preferences.profile

    override suspend fun save(profile: ChainProfile) = preferences.setProfile(profile)

    override suspend fun clear() = preferences.clearProfile()

    override suspend fun importFromSecretsEnv(text: String): Result<ChainProfile> =
        SecretsEnvParser.parse(text).onSuccess { preferences.setProfile(it) }
}
