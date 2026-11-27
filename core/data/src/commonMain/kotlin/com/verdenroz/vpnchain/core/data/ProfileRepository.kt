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

    /**
     * Replaces only the relay of the active profile, keeping its entry hop and
     * device exit. Without an active relay profile it is added as a connection.
     */
    suspend fun saveRelay(relay: ChainProfile)

    /** Sets or clears the active profile's entry hop; fails when it has no relay. */
    suspend fun setEntryHop(entry: WireGuardEntry?): Result<Unit>

    /** Sets or clears the active profile's device exit; fails when it has no relay. */
    suspend fun setDeviceExit(exit: WireGuardEntry?): Result<Unit>

    /** @return the id of the newly added profile, which becomes active. */
    suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun setActive(id: String)

    /** Removes every saved profile. */
    suspend fun clear()

    /** Adds the relay (and entry hop) in [text] as a connection, which becomes active only when [activate] is set. */
    suspend fun importFromSecretsEnv(text: String, activate: Boolean = true): Result<ChainProfile>

    /**
     * Imports a WireGuard `.conf`. With [intoActive] it becomes the entry hop of
     * the active connection when that has a relay; otherwise it is added as a
     * connection of its own, which becomes active only when [activate] is set.
     */
    suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean = true): Result<WireGuardEntry>
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

    override suspend fun saveRelay(relay: ChainProfile) {
        val active = preferences.profile.first()
        if (active?.relay == null) {
            add(name = relay.vpsIp, relay = relay, wireGuard = null)
            return
        }
        updateActive(active.id) { it.copy(relay = relay) }
    }

    override suspend fun setEntryHop(entry: WireGuardEntry?): Result<Unit> =
        updateActiveRelayProfile { it.copy(wireGuard = entry) }

    override suspend fun setDeviceExit(exit: WireGuardEntry?): Result<Unit> =
        updateActiveRelayProfile { it.copy(deviceExit = exit) }

    private suspend fun updateActiveRelayProfile(change: (SavedProfile) -> SavedProfile): Result<Unit> {
        val active = preferences.profile.first()
        if (active?.relay == null) return Result.failure(IllegalStateException("No relay connection is active"))
        updateActive(active.id, change)
        return Result.success(Unit)
    }

    private suspend fun updateActive(id: String, change: (SavedProfile) -> SavedProfile) {
        preferences.setProfiles(preferences.profiles.first().map { if (it.id == id) change(it) else it })
    }

    override suspend fun add(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?): String =
        insert(name, relay, wireGuard, activate = true)

    private suspend fun insert(name: String, relay: ChainProfile?, wireGuard: WireGuardEntry?, activate: Boolean): String {
        val id = newId()
        preferences.setProfiles(
            preferences.profiles.first() + SavedProfile(id = id, name = name, relay = relay, wireGuard = wireGuard),
        )
        if (activate) preferences.setActiveProfileId(id)
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

    override suspend fun importFromSecretsEnv(text: String, activate: Boolean): Result<ChainProfile> =
        SecretsEnvParser.parse(text)
            .onSuccess { insert(name = it.relay.vpsIp, relay = it.relay, wireGuard = it.entryHop, activate = activate) }
            .map { it.relay }

    override suspend fun importWireGuardConf(text: String, intoActive: Boolean, activate: Boolean): Result<WireGuardEntry> =
        WireGuardConfParser.parse(text).mapCatching { entry ->
            val active = preferences.profile.first()
            val relay = active?.relay
            if (intoActive && activate && relay != null) {
                require(active.deviceExit?.privateKey != entry.privateKey) { SAME_AS_EXIT }
                save(relay, entry)
            } else {
                insert(name = entry.endpointHost, relay = null, wireGuard = entry, activate = activate)
            }
            entry
        }
}

/**
 * Ids only have to be unique within one device's list, and are never shown or
 * transmitted, so a random hex string is enough.
 */
private fun randomProfileId(): String =
    (1..ID_LENGTH).map { HEX[Random.nextInt(HEX.length)] }.joinToString("")

private const val SAME_AS_EXIT = "That is this device's exit config. The entry hop needs its own."
private const val ID_LENGTH = 12
private const val HEX = "0123456789abcdef"
