package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.SavedProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileMigrationTest {

    /**
     * The upgrade path that matters: an install predating the profile list must
     * still find its chain, not look like a wiped config.
     */
    @Test
    fun `an install with only the legacy single profile still reports it`() = runTest {
        val store = newStore()
        store.edit { it[LEGACY_KEY] = LEGACY_JSON }
        val source = VpnChainPreferencesDataSource(store)

        val profiles = source.profiles.first()

        assertEquals(1, profiles.size)
        assertEquals("89.127.235.38", profiles.single().profile.vpsIp)
        assertEquals("89.127.235.38", source.profile.first()?.vpsIp)
    }

    /** Read-time only: a failed upgrade must be able to roll back. */
    @Test
    fun `reading a legacy profile does not rewrite storage`() = runTest {
        val store = newStore()
        store.edit { it[LEGACY_KEY] = LEGACY_JSON }
        val source = VpnChainPreferencesDataSource(store)

        source.profiles.first()

        assertEquals(LEGACY_JSON, store.data.first()[LEGACY_KEY])
        assertNull(store.data.first()[stringPreferencesKey("chain_profiles_json")])
    }

    @Test
    fun `writing the list retires the legacy slot`() = runTest {
        val store = newStore()
        store.edit { it[LEGACY_KEY] = LEGACY_JSON }
        val source = VpnChainPreferencesDataSource(store)

        source.setProfiles(listOf(SavedProfile(id = "a", name = "one", profile = profile("10.0.0.1"))))

        assertNull(store.data.first()[LEGACY_KEY])
        assertEquals(1, source.profiles.first().size)
    }

    @Test
    fun `the active profile is the selected one`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        source.setProfiles(
            listOf(
                SavedProfile(id = "a", name = "one", profile = profile("10.0.0.1")),
                SavedProfile(id = "b", name = "two", profile = profile("10.0.0.2")),
            ),
        )

        source.setActiveProfileId("b")

        assertEquals("10.0.0.2", source.profile.first()?.vpsIp)
        assertEquals("b", source.activeProfileId.first())
    }

    /**
     * A selection pointing at a deleted profile must not blank the chain — the
     * tunnel would have nothing to dial and the UI would look profile-less.
     */
    @Test
    fun `a stale selection falls back to the first profile`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        source.setProfiles(listOf(SavedProfile(id = "a", name = "one", profile = profile("10.0.0.1"))))
        source.setActiveProfileId("gone")

        assertEquals("10.0.0.1", source.profile.first()?.vpsIp)
        assertEquals("a", source.activeProfileId.first())
    }

    @Test
    fun `no profiles reports nothing active`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        assertTrue(source.profiles.first().isEmpty())
        assertNull(source.profile.first())
        assertNull(source.activeProfileId.first())
    }

    /**
     * `ChainProfile.entryHop` was once `protonEntry` and keeps that `@SerialName`.
     * Dropping it would deserialize to null under `ignoreUnknownKeys`, silently
     * turning an existing two-hop chain into a single-hop one.
     */
    @Test
    fun `a stored profile written under the old entry hop key keeps its entry hop`() = runTest {
        val store = newStore()
        store.edit { it[LEGACY_KEY] = LEGACY_JSON_WITH_ENTRY }
        val source = VpnChainPreferencesDataSource(store)

        val entry = assertNotNull(source.profile.first()?.entryHop)

        assertEquals("10.2.0.2/32", entry.address)
        assertEquals("entry.example", entry.endpointHost)
    }
}

private var storeCounter = 0

private fun newStore(): DataStore<Preferences> {
    val dir = File(System.getProperty("java.io.tmpdir"), "vpnchain-test-${storeCounter++}")
    dir.mkdirs()
    val file = File(dir, "test.preferences_pb").also(File::deleteOnExit)
    file.delete()
    return PreferenceDataStoreFactory.createWithPath { file.absolutePath.toPath() }
}

private fun profile(ip: String) = ChainProfile(
    vpsIp = ip,
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "short",
)

private val LEGACY_KEY = stringPreferencesKey("chain_profile_json")

private val LEGACY_JSON = """
    {"vpsIp":"89.127.235.38","vlessUuid":"uuid","realityPublicKey":"pubkey","shortId":"short"}
""".trimIndent()

private val LEGACY_JSON_WITH_ENTRY = """
    {"vpsIp":"89.127.235.38","vlessUuid":"uuid","realityPublicKey":"pubkey","shortId":"short",
     "protonEntry":{"privateKey":"priv","address":"10.2.0.2/32","peerPublicKey":"peer",
     "endpointHost":"entry.example","endpointPort":51820}}
""".trimIndent()
