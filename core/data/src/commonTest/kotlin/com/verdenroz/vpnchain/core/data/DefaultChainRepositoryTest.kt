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
import com.verdenroz.vpnchain.core.model.WarpMode
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
import kotlin.test.assertNotNull
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

    /**
     * Android's sticky restart and its Always-on VPN start both arrive at a
     * service holding no config, because the process that was handed one is
     * gone. Before the provider they failed outright on "no configuration".
     */
    @Test
    fun `installs a config provider the platform can start from unattended`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.profiles.stored.value = REPOSITORY_PROFILE
        runCurrent()

        val config = world.controller.configProvider?.invoke()

        assertNotNull(config, "an unattended start has to be able to render its own config")
    }

    /**
     * A start the OS made on a standing instruction is still the user wanting
     * to be connected — without recording it, the first drop afterwards reads
     * as a disconnect they chose, and nothing reconnects.
     */
    @Test
    fun `an unattended start records connection intent`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.profiles.stored.value = REPOSITORY_PROFILE
        runCurrent()

        world.controller.configProvider?.invoke()
        runCurrent()

        assertTrue(world.repository.connectionIntent.first())
    }

    @Test
    fun `release stops the controller without touching intent`() = runTest {
        val world = RepositoryWorld(backgroundScope)
        world.preferences.setConnectionIntent(true)
        runCurrent()

        world.repository.release()

        assertEquals(1, world.controller.releaseCalls)
        assertEquals(0, world.controller.stopCalls, "release is the platform's call, not a stop")
        assertTrue(world.repository.connectionIntent.first(), "release is not a disconnect")
    }
}

private val REPOSITORY_PROFILE = ChainProfile(
    vpsIp = "203.0.113.10",
    serverPort = 443,
    vlessUuid = "8f1c6d2e-4b7a-4c31-9f0e-2a5d6c8b1e33",
    realityPublicKey = "0KcF1hV0Qm5wZ1YHc2xJ8s3nT4pR7uD9eA2bC6gK1lM",
    shortId = "a1b2c3d4",
    sni = "www.example.com",
)

private class RepositoryWorld(scope: CoroutineScope) {
    val controller = FakeTunnelController()
    val profiles = StubProfileRepository()
    val preferences = VpnChainPreferencesDataSource(InMemoryPreferences())
    val repository: ChainRepository = DefaultChainRepository(
        controller = controller,
        profileRepository = profiles,
        settingsRepository = StubSettingsRepository(),
        warpRepository = StubWarpRepository(),
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
    var releaseCalls = 0
    var configProvider: (suspend () -> String?)? = null

    override suspend fun start(configJson: String, killSwitchEnabled: Boolean) = Unit
    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun release() {
        releaseCalls++
    }

    override fun installConfigProvider(provider: suspend () -> String?) {
        configProvider = provider
    }
}

private class StubProfileRepository : ProfileRepository {
    val stored = MutableStateFlow<ChainProfile?>(null)
    override val profile: Flow<ChainProfile?> = stored
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
    override suspend fun setWarpMode(mode: WarpMode) = Unit
    override suspend fun setWarpDomains(domains: List<String>) = Unit
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setCloseToTray(enabled: Boolean) = Unit
    override val autostartSupported = false
    override suspend fun setAutoStartOnLogin(enabled: Boolean) = Result.success(Unit)
}

private class StubWarpRepository : WarpRepository {
    override suspend fun exit() = null
}

private class SilentLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
