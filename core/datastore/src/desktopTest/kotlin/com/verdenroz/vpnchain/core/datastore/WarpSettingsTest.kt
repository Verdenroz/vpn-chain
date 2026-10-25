package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    fun `a fresh install routes only blocked sites and lists none by hand`() = runTest {
        val settings = VpnChainPreferencesDataSource(newStore()).settings.first()

        assertEquals(WarpMode.BlockedSites, settings.warpMode)
        assertTrue(settings.warpDomains.isEmpty())
    }

    @Test
    fun `a list that is still the old seed reads as empty`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        source.setWarpDomains(OLD_SEED)

        assertTrue(source.settings.first().warpDomains.isEmpty())
    }

    @Test
    fun `an edited copy of the old seed is kept`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        source.setWarpDomains(OLD_SEED + "mychart.chsli.org")

        assertEquals(OLD_SEED + "mychart.chsli.org", source.settings.first().warpDomains)
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

        assertEquals(WarpMode.BlockedSites, VpnChainPreferencesDataSource(store).settings.first().warpMode)
    }

    /**
     * Emptying the list is a real choice — it means "route nothing" — so it has
     * to survive, rather than being refilled from the built-in list next read.
     */
    @Test
    fun `an emptied domain list stays empty`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        source.setWarpDomains(emptyList())

        assertTrue(source.settings.first().warpDomains.isEmpty())
    }

    @Test
    fun `an edited domain list is stored as given`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        source.setWarpDomains(listOf("example.com"))

        assertEquals(listOf("example.com"), source.settings.first().warpDomains)
    }

    @Test
    fun `learning is on by default and the switch persists`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        assertTrue(source.settings.first().learnBlockedSites)

        source.setLearnBlockedSites(false)

        assertEquals(false, source.settings.first().learnBlockedSites)
    }

    @Test
    fun `a learned site survives a reread and is replaced by domain`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        val site = LearnedSite("example.com", "403", 5L, LearnedRoute.Warp)

        source.putLearnedSite(site)
        source.putLearnedSite(site.copy(reason = "challenge"))

        assertEquals(listOf(site.copy(reason = "challenge")), source.learnedSites.first())
    }

    @Test
    fun `removing a learned site records when it may be learned again`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())
        source.putLearnedSite(LearnedSite("example.com", "403", 5L, LearnedRoute.Warp))

        source.removeLearnedSite("example.com", suppressUntilMillis = 99L)

        assertTrue(source.learnedSites.first().isEmpty())
        assertEquals(mapOf("example.com" to 99L), source.learnedSuppressions.first())
    }

    private val OLD_SEED = listOf(
        "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com",
        "chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com",
    )

    private fun newStore(): DataStore<Preferences> {
        val file = File.createTempFile("warp-settings", ".preferences_pb").apply { delete() }
        file.deleteOnExit()
        return PreferenceDataStoreFactory.createWithPath { file.absolutePath.toPath() }
    }
}
