package com.verdenroz.vpnchain.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verdenroz.vpnchain.core.model.DEFAULT_WARP_DOMAINS
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
    fun `a fresh install routes only the blocked sites through the tail`() = runTest {
        val settings = VpnChainPreferencesDataSource(newStore()).settings.first()

        assertEquals(WarpMode.BlockedSites, settings.warpMode)
        assertEquals(DEFAULT_WARP_DOMAINS, settings.warpDomains)
        assertTrue("reddit.com" in settings.warpDomains)
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
    fun `an edited domain list replaces the built-in one outright`() = runTest {
        val source = VpnChainPreferencesDataSource(newStore())

        source.setWarpDomains(listOf("example.com"))

        assertEquals(listOf("example.com"), source.settings.first().warpDomains)
    }

    private fun newStore(): DataStore<Preferences> {
        val file = File.createTempFile("warp-settings", ".preferences_pb").apply { delete() }
        file.deleteOnExit()
        return PreferenceDataStoreFactory.createWithPath { file.absolutePath.toPath() }
    }
}
