package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChainRepositoryTest {

    /**
     * The regression this guards: Android's notification key and an OS revoke
     * stop the tunnel without going through [ChainRepository.disconnect], and a
     * stale intent makes the supervisor treat that as a drop and reconnect.
     */
    @Test
    fun `clears connection intent when the tunnel is stopped outside the app`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.preferences.setConnectionIntent(true)
        runCurrent()

        world.controller.userStops.emit(Unit)
        runCurrent()

        assertFalse(world.repository.connectionIntent.first())
    }

    @Test
    fun `disconnect clears intent and stops the controller`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.preferences.setConnectionIntent(true)
        runCurrent()

        world.repository.disconnect()

        assertFalse(world.repository.connectionIntent.first())
        assertEquals(1, world.controller.stopCalls)
    }

    @Test
    fun `leaves intent alone while the tunnel stays up`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.preferences.setConnectionIntent(true)
        runCurrent()

        assertTrue(world.repository.connectionIntent.first())
    }
}

private class RepositoryWorld(scope: CoroutineScope) {
    val controller = FakeTunnelController()
    val preferences = VpnChainPreferencesDataSource(InMemoryPreferences())
    val repository: ChainRepository = DefaultChainRepository(
        controller = controller,
        profileRepository = StubProfileRepository(),
        settingsRepository = StubSettingsRepository(),
        preferences = preferences,
        logger = SilentLogger(),
        scope = scope,
    )
}

private class InMemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

private class FakeTunnelController : TunnelController {
    override val status = MutableStateFlow(ChainStatus())
    override val logs = MutableSharedFlow<String>()
    override val userStops = MutableSharedFlow<Unit>()
    var stopCalls = 0

    override suspend fun start(configJson: String, killSwitchEnabled: Boolean) = Unit
    override suspend fun stop() {
        stopCalls++
    }
}

private class StubProfileRepository : ProfileRepository {
    override val profile: Flow<ChainProfile?> = MutableStateFlow(null)
    override val profiles: Flow<List<SavedProfile>> = MutableStateFlow(emptyList())
    override val activeProfileId: Flow<String?> = MutableStateFlow(null)
    override suspend fun save(profile: ChainProfile) = Unit
    override suspend fun add(name: String, profile: ChainProfile) = "id"
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String) =
        Result.failure<ChainProfile>(IllegalStateException("not used"))
}

private class StubSettingsRepository : SettingsRepository {
    override val settings: Flow<UserSettings> = MutableStateFlow(
        UserSettings(themeConfig = ThemeConfig.FOLLOW_SYSTEM),
    )
    override suspend fun setThemeConfig(themeConfig: ThemeConfig) = Unit
    override suspend fun setSystemWideTun(enabled: Boolean) = Unit
    override suspend fun setKillSwitchEnabled(enabled: Boolean) = Unit
    override suspend fun setDnsFilter(filter: DnsFilter) = Unit
    override suspend fun setEntryHopEnabled(enabled: Boolean) = Unit
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setCloseToTray(enabled: Boolean) = Unit
    override val autostartSupported = false
    override suspend fun setAutoStartOnLogin(enabled: Boolean) = Result.success(Unit)
}

private class SilentLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
