package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.config.SecretsEnvParser
import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ConnectionKind
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.WireGuardEntry
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
    val profile: Flow<SavedProfile?>
    val activeProfileId: Flow<String?>

    /**
     * Replaces the active profile's relay and entry hop in place, keeping its id
     * and name. A WireGuard-only profile is never given a relay this way: the
     * relay is added as a connection of its own.
     */
    suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?)

    /** @return the id of the newly added profile, which becomes active. */
    suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun setActive(id: String)

    /** Removes every saved profile. */
    suspend fun clear()

    suspend fun importFromSecretsEnv(text: String): Result<ChainProfile>

    /**
     * Imports a WireGuard `.conf`. With [intoActive] it becomes the entry hop of
     * the active connection when that has a relay; otherwise it is added as a
     * connection of its own, and becomes active.
     */
    suspend fun importWireGuardConf(text: String, intoActive: Boolean): Result<WireGuardEntry>
}

internal class DefaultProfileRepository(
    private val preferences: VpnChainPreferencesDataSource,
    private val newId: () -> String = ::randomProfileId,
) : ProfileRepository {

    override val profiles: Flow<List<SavedProfile>> = preferences.profiles
    override val profile: Flow<SavedProfile?> = preferences.profile
    override val activeProfileId: Flow<String?> = preferences.activeProfileId

    override suspend fun save(relay: ChainProfile, entryHop: WireGuardEntry?) {
        val active = preferences.profile.first()
        if (active == null || active.kind == ConnectionKind.WireGuard) {
            add(name = relay.vpsIp, relay = relay, wireGuard = entryHop)
            return
        }
        preferences.setProfiles(
            preferences.profiles.first().map { if (it.id == active.id) it.copy(relay = relay, wireGuard = entryHop) else it },
        )
    }

    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?): String {
        val id = newId()
        preferences.setProfiles(
            preferences.profiles.first() + SavedProfile(id = id, name = name, relay = relay, wireGuard = wireGuard),
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
        SecretsEnvParser.parse(text)
            .onSuccess { add(name = it.relay.vpsIp, relay = it.relay, wireGuard = it.entryHop) }
            .map { it.relay }

    override suspend fun importWireGuardConf(text: String, intoActive: Boolean): Result<WireGuardEntry> =
        WireGuardConfParser.parse(text).onSuccess { entry ->
            val relay = preferences.profile.first()?.relay
            if (intoActive && relay != null) {
                save(relay, entry)
            } else {
                add(name = entry.endpointHost, relay = null, wireGuard = entry)
            }
        }
}

/**
 * Ids only have to be unique within one device's list, and are never shown or
 * transmitted, so a random hex string is enough.
 */
private fun randomProfileId(): String =
    (1..ID_LENGTH).map { HEX[Random.nextInt(HEX.length)] }.joinToString("")

private const val ID_LENGTH = 12
private const val HEX = "0123456789abcdef"
