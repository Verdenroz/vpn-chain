package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ConnectivityRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChainSupervisorTest {

    @Test
    fun `connects on launch when the setting is on and a profile exists`() = runTest {
        val world = SupervisorWorld(this, settings = settings(autoConnectOnLaunch = true))

        world.run()
        advanceTimeBy(SETTLE_MS)

        assertEquals(1, world.chain.connectCalls)
    }

    @Test
    fun `does not connect on launch without a profile`() = runTest {
        val world = SupervisorWorld(this, settings = settings(autoConnectOnLaunch = true))
        world.profiles.stored.value = null

        world.run()
        advanceTimeBy(SETTLE_MS)

        assertEquals(0, world.chain.connectCalls)
    }

    @Test
    fun `does not connect on launch when the setting is off`() = runTest {
        val world = SupervisorWorld(this, settings = settings(autoConnectOnLaunch = false))

        world.run()
        advanceTimeBy(SETTLE_MS)

        assertEquals(0, world.chain.connectCalls)
    }

    @Test
    fun `reconnects after an unexpected drop while the user wants to be connected`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = true
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Error)
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(1) + SETTLE_MS)

        assertEquals(1, world.chain.reconnectCalls)
    }

    /**
     * The whole point of tracking intent separately: a user-initiated
     * disconnect leaves the tunnel down, and reconnecting them would make the
     * disconnect button useless.
     */
    @Test
    fun `does not reconnect after the user disconnects`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = false
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Disconnected)
        advanceTimeBy(ReconnectPolicy.MAX_DELAY_MS)

        assertEquals(0, world.chain.reconnectCalls)
    }

    /**
     * The Android notification's Disconnect key clears intent a moment after the
     * tunnel is already down, so the check that has to hold is the one after the
     * backoff — not the one that entered it.
     */
    @Test
    fun `abandons a pending retry when the user disconnects mid-backoff`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = true
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Disconnected)
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(1) / 2)
        world.chain.connectionIntent.value = false
        advanceTimeBy(ReconnectPolicy.MAX_DELAY_MS)

        assertEquals(0, world.chain.reconnectCalls)
    }

    @Test
    fun `does not reconnect when auto-reconnect is switched off`() = runTest {
        val world = SupervisorWorld(
            this,
            settings = settings(autoReconnect = false),
            initialState = TunnelState.Connected,
        )
        world.chain.connectionIntent.value = true
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Error)
        advanceTimeBy(ReconnectPolicy.MAX_DELAY_MS)

        assertEquals(0, world.chain.reconnectCalls)
    }

    @Test
    fun `backs off further after each failed attempt`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = true
        world.chain.reconnectKeepsFailing = true
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Error)

        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(1) + 1)
        assertEquals(1, world.chain.reconnectCalls)

        // Still only one retry partway into the second, longer backoff.
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(2) / 2)
        assertEquals(1, world.chain.reconnectCalls)

        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(2))
        assertEquals(2, world.chain.reconnectCalls)
    }

    @Test
    fun `starts over from the base delay after a successful reconnect`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = true
        world.chain.reconnectKeepsFailing = true
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Error)
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(1) + 1)
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(2) + 1)
        assertEquals(2, world.chain.reconnectCalls)

        world.chain.emit(TunnelState.Connected)
        advanceTimeBy(SETTLE_MS)
        world.chain.emit(TunnelState.Error)

        // Base delay again: had the counter not reset, this would still be
        // waiting out attempt 3's much longer backoff.
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(1) + 1)
        assertEquals(3, world.chain.reconnectCalls)
    }

    /**
     * A tunnel that dropped because the Wi-Fi went away should come back when
     * the Wi-Fi does, not after the remaining backoff.
     */
    @Test
    fun `retries immediately when the network comes back mid-backoff`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = true
        world.chain.reconnectKeepsFailing = true
        world.network.reachable.value = false
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Error)
        advanceTimeBy(ReconnectPolicy.delayForAttemptMillis(1) / 4)
        assertEquals(0, world.chain.reconnectCalls, "backoff should not have elapsed yet")

        world.network.reachable.value = true
        advanceTimeBy(10)

        assertEquals(1, world.chain.reconnectCalls, "network return should short-circuit the backoff")
    }

    @Test
    fun `leaves a connecting tunnel alone`() = runTest {
        val world = SupervisorWorld(this, initialState = TunnelState.Connected)
        world.chain.connectionIntent.value = true
        world.run()
        advanceTimeBy(SETTLE_MS)

        world.chain.emit(TunnelState.Connecting)
        advanceTimeBy(ReconnectPolicy.MAX_DELAY_MS)

        assertEquals(0, world.chain.reconnectCalls)
    }
}

/** Enough virtual time for the supervisor's non-delayed work to run. */
private const val SETTLE_MS = 100L

private fun settings(
    autoConnectOnLaunch: Boolean = false,
    autoReconnect: Boolean = true,
) = UserSettings(
    themeConfig = ThemeConfig.FOLLOW_SYSTEM,
    autoConnectOnLaunch = autoConnectOnLaunch,
    autoReconnect = autoReconnect,
)

private val SUPERVISED_PROFILE = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
    realityPublicKey = "key",
    shortId = "ab",
)

private class SupervisorWorld(
    private val scope: TestScope,
    settings: UserSettings = settings(),
    initialState: TunnelState = TunnelState.Disconnected,
) {
    val chain = FakeChainRepository(initialState)
    val profiles = FakeProfileRepository()
    val settingsRepo = FakeSettingsRepository(settings)
    val network = FakeConnectivity()

    private val supervisor = ChainSupervisor(
        chain = chain,
        profiles = profiles,
        settings = settingsRepo,
        network = network,
    )

    /** backgroundScope so the supervisor's endless retry loop is torn down with the test. */
    fun run() {
        scope.backgroundScope.launch { supervisor.run() }
    }
}

private class FakeChainRepository(initialState: TunnelState) : ChainRepository {
    private val statusFlow = MutableStateFlow(ChainStatus(state = initialState))
    override val status: Flow<ChainStatus> = statusFlow
    override val connectionIntent = MutableStateFlow(false)
    override val stats = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit

    var connectCalls = 0
    var reconnectCalls = 0
    var reconnectKeepsFailing = false

    fun emit(state: TunnelState) {
        statusFlow.value = statusFlow.value.copy(state = state)
    }

    override suspend fun connect(): Result<Unit> {
        connectCalls++
        connectionIntent.value = true
        emit(TunnelState.Connected)
        return Result.success(Unit)
    }

    override suspend fun reconnect(): Result<Unit> {
        reconnectCalls++
        if (reconnectKeepsFailing) {
            // A failed attempt leaves the tunnel where it was: still down, and
            // with no new status emission for the supervisor to react to.
            return Result.failure(IllegalStateException("still down"))
        }
        emit(TunnelState.Connected)
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connectionIntent.value = false
        emit(TunnelState.Disconnected)
    }
}

private class FakeProfileRepository : ProfileRepository {
    val stored = MutableStateFlow<ChainProfile?>(SUPERVISED_PROFILE)
    override val profile: Flow<ChainProfile?> = stored
    override val profiles: Flow<List<SavedProfile>> = MutableStateFlow(emptyList())
    override val activeProfileId: Flow<String?> = MutableStateFlow(null)
    override suspend fun save(profile: ChainProfile) = Unit
    override suspend fun add(name: String, profile: ChainProfile) = "id"
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String) = Result.success(SUPERVISED_PROFILE)
}

private class FakeSettingsRepository(value: UserSettings) : SettingsRepository {
    val state = MutableStateFlow(value)
    override val settings: Flow<UserSettings> = state
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

private class FakeConnectivity : ConnectivityRepository {
    val reachable = MutableStateFlow(true)
    override val online: Flow<Boolean> = reachable
}
