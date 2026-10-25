package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarpSettingsTest {

    /**
     * The default is an opsec decision, not a convenience one: the tail is
     * dialled through the relay, so Cloudflare sees the VPS's address against
     * every destination it carries. Confining that to the sites that actually
     * refuse the relay keeps a box the user controls as the exit for the rest.
     */
    @Test
    fun `a fresh install routes only the sites detection finds`() = runTest {
        val settings = VpnChainPreferencesDataSource(newStore()).settings.first()

        assertEquals(WarpMode.Automatic, settings.warpMode)
    }

    @Test
    fun `a stored mode survives a reread`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        source.setWarpMode(WarpMode.AllTraffic)

        assertEquals(WarpMode.AllTraffic, source.settings.first().warpMode)
    }

    /** An unreadable value must fall back to the default, not to no tail. */
    @Test
    fun `a mode that no longer exists reads as the default`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey("warp_mode")] = "SomethingRetired" }

        assertEquals(WarpMode.Automatic, VpnChainPreferencesDataSource(store).settings.first().warpMode)
    }

    @Test
    fun `the retired manual list is dropped and its mode renamed`() = runTest {
        val before = preferencesOf(
            stringPreferencesKey("warp_mode") to "BlockedSites",
            stringPreferencesKey("warp_domains_json") to "[\"example.com\"]",
        )

        assertTrue(retiredSettingsMigration.shouldMigrate(before))
        val after = retiredSettingsMigration.migrate(before)

        assertEquals("Automatic", after[stringPreferencesKey("warp_mode")])
        assertEquals(null, after[stringPreferencesKey("warp_domains_json")])
        assertEquals(false, retiredSettingsMigration.shouldMigrate(after))
    }

    @Test
    fun `learning is on by default and the switch persists`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        assertTrue(source.settings.first().learnBlockedSites)

        source.setLearnBlockedSites(false)

        assertEquals(false, source.settings.first().learnBlockedSites)
    }

    @Test
    fun `failing open is on by default and the switch persists`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        assertTrue(source.settings.first().failOpen)

        source.setFailOpen(false)

        assertEquals(false, source.settings.first().failOpen)
    }

    @Test
    fun `a learned site survives a reread and is replaced by domain`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        val site = LearnedSite("example.com", BlockReason.Forbidden, 5L, LearnedRoute.Warp)

        source.putLearnedSite(site)
        source.putLearnedSite(site.copy(reason = BlockReason.RateLimited))

        assertEquals(listOf(site.copy(reason = BlockReason.RateLimited)), source.learnedSites.first())
    }

    @Test
    fun `removing a learned site records when it may be learned again`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        source.putLearnedSite(LearnedSite("example.com", BlockReason.Forbidden, 5L, LearnedRoute.Warp))

        source.removeLearnedSite("example.com", suppressUntilMillis = 99L)

        assertTrue(source.learnedSites.first().isEmpty())
        assertEquals(mapOf("example.com" to 99L), source.learnedSuppressions.first())
    }

    private fun newStore(): DataStore<Preferences> {
        val file = File.createTempFile("warp-settings", ".preferences_pb").apply { delete() }
        file.deleteOnExit()
        return PreferenceDataStoreFactory.createWithPath(migrations = listOf(retiredSettingsMigration)) { file.absolutePath.toPath() }
    }
}
