package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.SecretsEnvParser
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.SavedProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * Reads and persists the saved chain identities ([SavedProfile]).
 *
 * [profile] stays the *active* one, so everything that only wants "the chain to
 * dial" — the tunnel, the route, the supervisor — is unaffected by there now
 * being more than one.
 */
interface ProfileRepository {
    val profiles: Flow<List<SavedProfile>>
    val profile: Flow<ChainProfile?>
    val activeProfileId: Flow<String?>

    /** Replaces the active profile in place, keeping its id and name. */
    suspend fun save(profile: ChainProfile)

    /** @return the id of the newly added profile, which becomes active. */
    suspend fun add(name: String, profile: ChainProfile): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun setActive(id: String)

    /** Removes every saved profile. */
    suspend fun clear()

    suspend fun importFromSecretsEnv(text: String): Result<ChainProfile>
}

internal class DefaultProfileRepository(
    private val preferences: VpnChainPreferencesDataSource,
    private val newId: () -> String = ::randomProfileId,
) : ProfileRepository {

    override val profiles: Flow<List<SavedProfile>> = preferences.profiles
    override val profile: Flow<ChainProfile?> = preferences.profile
    override val activeProfileId: Flow<String?> = preferences.activeProfileId

    override suspend fun save(profile: ChainProfile) {
        val saved = preferences.profiles.first()
        val activeId = preferences.activeProfileId.first()
        if (saved.isEmpty() || activeId == null) {
            add(name = profile.vpsIp, profile = profile)
            return
        }
        preferences.setProfiles(
            saved.map { if (it.id == activeId) it.copy(profile = profile) else it },
        )
    }

    override suspend fun add(name: String, profile: ChainProfile): String {
        val id = newId()
        preferences.setProfiles(
            preferences.profiles.first() + SavedProfile(id = id, name = name, profile = profile),
        )
        preferences.setActiveProfileId(id)
        return id
    }

    override suspend fun rename(id: String, name: String) {
        preferences.setProfiles(
            preferences.profiles.first().map { if (it.id == id) it.copy(name = name) else it },
        )
    }

    override suspend fun delete(id: String) {
        val remaining = preferences.profiles.first().filterNot { it.id == id }
        preferences.setProfiles(remaining)
        // Deleting the active profile falls through to whatever is left, rather
        // than leaving the chain pointed at nothing.
        if (preferences.activeProfileId.first() == id) {
            remaining.firstOrNull()?.let { preferences.setActiveProfileId(it.id) }
        }
    }

    override suspend fun setActive(id: String) = preferences.setActiveProfileId(id)

    override suspend fun clear() = preferences.clearProfile()

    override suspend fun importFromSecretsEnv(text: String): Result<ChainProfile> =
        SecretsEnvParser.parse(text).onSuccess { add(name = it.vpsIp, profile = it) }
}

/**
 * Ids only have to be unique within one device's list, and are never shown or
 * transmitted, so a random hex string is enough.
 */
private fun randomProfileId(): String =
    (1..ID_LENGTH).map { HEX[Random.nextInt(HEX.length)] }.joinToString("")

private const val ID_LENGTH = 12
private const val HEX = "0123456789abcdef"
