package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ConnectionKind
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("203.0.113.10", profiles.single().relay?.vpsIp)
        assertEquals("203.0.113.10", source.profile.first()?.relay?.vpsIp)
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

        source.setProfiles(listOf(SavedProfile(id = "a", name = "one", relay = profile("10.0.0.1"))))

        assertNull(store.data.first()[LEGACY_KEY])
        assertEquals(1, source.profiles.first().size)
    }

    @Test
    fun `the active profile is the selected one`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        source.setProfiles(
            listOf(
                SavedProfile(id = "a", name = "one", relay = profile("10.0.0.1")),
                SavedProfile(id = "b", name = "two", relay = profile("10.0.0.2")),
            ),
        )

        source.setActiveProfileId("b")

        assertEquals("10.0.0.2", source.profile.first()?.relay?.vpsIp)
        assertEquals("b", source.activeProfileId.first())
    }

    /**
     * A selection pointing at a deleted profile must not blank the chain — the
     * tunnel would have nothing to dial and the UI would look profile-less.
     */
    @Test
    fun `a stale selection falls back to the first profile`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        source.setProfiles(listOf(SavedProfile(id = "a", name = "one", relay = profile("10.0.0.1"))))
        source.setActiveProfileId("gone")

        assertEquals("10.0.0.1", source.profile.first()?.relay?.vpsIp)
        assertEquals("a", source.activeProfileId.first())
    }

    @Test
    fun `a legacy profile with an entry hop moves it to the wireguard part`() = runTest {
        val store = newStore()
        store.edit { it[LEGACY_KEY] = LEGACY_WITH_ENTRY_JSON }

        val saved = VpnChainPreferencesDataSource(store).profiles.first().single()

        assertEquals(ConnectionKind.Chain, saved.kind)
        assertEquals("legacy", saved.id)
        assertEquals("203.0.113.10", saved.name)
        assertEquals("203.0.113.10", saved.relay?.vpsIp)
        assertEquals("203.0.113.9", saved.wireGuard?.endpointHost)
        assertEquals("10.2.0.1", saved.wireGuard?.dns)
    }

    @Test
    fun `a stored list in the nested shape keeps its entry hop`() = runTest {
        val store = newStore()
        store.edit { it[LIST_KEY] = """[{"id":"a","name":"one","profile":$LEGACY_WITH_ENTRY_JSON}]""" }

        val saved = VpnChainPreferencesDataSource(store).profiles.first().single()

        assertEquals("a", saved.id)
        assertEquals("203.0.113.10", saved.relay?.vpsIp)
        assertEquals("203.0.113.9", saved.wireGuard?.endpointHost)
    }

    @Test
    fun `a stored list in the nested shape without an entry hop is relay only`() = runTest {
        val store = newStore()
        store.edit { it[LIST_KEY] = """[{"id":"a","name":"one","profile":$LEGACY_JSON}]""" }

        val saved = VpnChainPreferencesDataSource(store).profiles.first().single()

        assertEquals(ConnectionKind.Relay, saved.kind)
        assertNull(saved.wireGuard)
    }

    @Test
    fun `reading the nested shape does not rewrite storage`() = runTest {
        val store = newStore()
        val raw = """[{"id":"a","name":"one","profile":$LEGACY_JSON}]"""
        store.edit { it[LIST_KEY] = raw }

        VpnChainPreferencesDataSource(store).profiles.first()

        assertEquals(raw, store.data.first()[LIST_KEY])
    }

    @Test
    fun `a wireguard only connection survives a write and read`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        val wireGuard = SavedProfile(id = "w", name = "Proton", wireGuard = entry())

        source.setProfiles(listOf(wireGuard))

        assertEquals(wireGuard, source.profiles.first().single())
        assertEquals(ConnectionKind.WireGuard, source.profile.first()?.kind)
    }

    @Test
    fun `one unreadable entry leaves the rest of the list readable`() = runTest {
        val store = newStore()
        store.edit { it[LIST_KEY] = """[{"id":"bad","name":"x"},{"id":"a","name":"one","relay":${relayJson()}}]""" }

        val profiles = VpnChainPreferencesDataSource(store).profiles.first()

        assertEquals(listOf("a"), profiles.map { it.id })
    }

    @Test
    fun `saving keeps an entry this build cannot read`() = runTest {
        val store = newStore()
        store.edit { it[LIST_KEY] = """[{"id":"bad","name":"x"},{"id":"a","name":"one","relay":${relayJson()}}]""" }
        val source = VpnChainPreferencesDataSource(store)

        source.setProfiles(source.profiles.first() + SavedProfile(id = "b", name = "two", relay = profile("10.0.0.2")))

        val raw = store.data.first()[LIST_KEY].orEmpty()
        assertTrue("\"bad\"" in raw)
        assertEquals(listOf("a", "b"), source.profiles.first().map { it.id })
    }

    @Test
    fun `no profiles reports nothing active`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        assertTrue(source.profiles.first().isEmpty())
        assertNull(source.profile.first())
        assertNull(source.activeProfileId.first())
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

private fun entry() = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = "203.0.113.9",
)

private fun relayJson() = LEGACY_JSON

private val LIST_KEY = stringPreferencesKey("chain_profiles_json")

private val LEGACY_KEY = stringPreferencesKey("chain_profile_json")

private val LEGACY_JSON = """
    {"vpsIp":"203.0.113.10","vlessUuid":"uuid","realityPublicKey":"pubkey","shortId":"short"}
""".trimIndent()

private val LEGACY_WITH_ENTRY_JSON = """
    {"vpsIp":"203.0.113.10","vlessUuid":"uuid","realityPublicKey":"pubkey","shortId":"short",
    "entryHop":{"privateKey":"priv","address":"10.2.0.2/32","peerPublicKey":"peer",
    "endpointHost":"203.0.113.9","endpointPort":51820,"dns":"10.2.0.1"}}
""".trimIndent().replace("\n", "")
