package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.OriginRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.geoip.HopLocation
import com.verdenroz.vpnchain.core.geoip.NetworkProbe
import com.verdenroz.vpnchain.core.geoip.PublicIpSample
import com.verdenroz.vpnchain.core.geoip.RouteGeolocator
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private const val ORIGIN_IP = "72.14.201.9"
private const val VPS_IP = "89.127.235.38"
private const val ENTRY_HOST = "146.70.198.34"

class ObserveChainRouteUseCaseTest {

    @Test
    fun `exit hop is measured from the live sample while connected`() = runTest {
        val world = TestWorld(status = connectedStatus(exitIp = null))
        world.probe.next = PublicIpSample(VPS_IP, elapsedMs = 412)

        val exit = world.resolve().hop(HopRole.Exit)

        assertEquals(VPS_IP, exit?.ip)
        assertEquals(HopEvidence.Measured, exit?.evidence)
    }

    @Test
    fun `exit hop falls back to the configured vps when nothing has been observed`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))
        world.probe.next = null

        val exit = world.resolve().hop(HopRole.Exit)

        assertEquals(VPS_IP, exit?.ip)
        assertEquals(HopEvidence.Configured, exit?.evidence)
    }

    /** The tail changes what the world sees, so the exit leg should say so. */
    @Test
    fun `the exit names the warp tail once the measured address is not the relay`() = runTest {
        val world = TestWorld(
            status = connectedStatus(exitIp = null),
            settings = UserSettings(warpMode = WarpMode.AllTraffic),
        )
        world.probe.next = PublicIpSample("104.28.194.140", elapsedMs = 300)

        val exit = world.resolve().hop(HopRole.Exit)

        assertEquals("104.28.194.140", exit?.ip)
        assertEquals("vless · reality → warp", exit?.via)
    }

    /**
     * Registration fails on a network that blocks Cloudflare, and the chain
     * comes up one hop shorter. Drawing a tail there would claim a hop that
     * isn't carrying anything.
     */
    @Test
    fun `no tail is named when the exit is still the relay`() = runTest {
        val world = TestWorld(
            status = connectedStatus(exitIp = null),
            settings = UserSettings(warpMode = WarpMode.AllTraffic),
        )
        world.probe.next = PublicIpSample(VPS_IP, elapsedMs = 300)

        assertEquals("vless · reality · tcp 443", world.resolve().hop(HopRole.Exit)?.via)
    }

    @Test
    fun `no tail is named when the mode is off`() = runTest {
        val world = TestWorld(
            status = connectedStatus(exitIp = null),
            settings = UserSettings(warpMode = WarpMode.Off),
        )
        world.probe.next = PublicIpSample("104.28.194.140", elapsedMs = 300)

        assertEquals("vless · reality · tcp 443", world.resolve().hop(HopRole.Exit)?.via)
    }

    @Test
    fun `origin is measured and recorded while disconnected`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))
        world.probe.next = PublicIpSample(ORIGIN_IP, elapsedMs = 30)

        val origin = world.resolve().hop(HopRole.Origin)

        assertEquals(ORIGIN_IP, origin?.ip)
        assertEquals(HopEvidence.Measured, origin?.evidence)
        assertEquals(ORIGIN_IP, world.origins.recorded)
    }

    /** The outage that started this: a probe issued before the app noticed the
     *  tunnel returns the exit, and recording it would rename the exit "home". */
    @Test
    fun `a sample matching the vps is never recorded as the origin`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))
        world.probe.next = PublicIpSample(VPS_IP, elapsedMs = 900)

        world.resolve()

        assertNull(world.origins.recorded)
    }

    @Test
    fun `a stored origin equal to the exit is not shown as the origin`() = runTest {
        val world = TestWorld(
            status = connectedStatus(exitIp = VPS_IP),
            lastOrigin = VPS_IP,
        )

        val origin = world.resolve().hop(HopRole.Origin)

        assertNull(origin?.ip)
        assertEquals(HopEvidence.Unknown, origin?.evidence)
    }

    @Test
    fun `origin is recalled rather than probed while connected`() = runTest {
        val world = TestWorld(status = connectedStatus(exitIp = VPS_IP), lastOrigin = ORIGIN_IP)
        world.probe.next = PublicIpSample(VPS_IP, elapsedMs = 400)

        val origin = world.resolve().hop(HopRole.Origin)

        assertEquals(ORIGIN_IP, origin?.ip)
        assertEquals(HopEvidence.Recalled, origin?.evidence)
        assertNull(world.origins.recorded)
    }

    @Test
    fun `origin carries in every state because traffic always starts there`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))

        val route = world.resolve()

        assertTrue(route.hop(HopRole.Origin)?.carrying == true)
        assertTrue(route.hop(HopRole.Exit)?.carrying == false)
    }

    @Test
    fun `entry hop is absent on a relay-only chain`() = runTest {
        val world = TestWorld(
            status = connectedStatus(exitIp = VPS_IP),
            profile = profile(entry = null),
        )

        val route = world.resolve()

        assertNull(route.hop(HopRole.Entry))
        assertEquals(listOf(HopRole.Origin, HopRole.Exit), route.hops.map { it.role })
    }

    /** The readout must draw the topology as dialed: a disabled entry is not a hop. */
    @Test
    fun `entry hop is absent when the setting disables it`() = runTest {
        val world = TestWorld(
            status = connectedStatus(exitIp = VPS_IP),
            settings = UserSettings(entryHopEnabled = false),
        )

        val route = world.resolve()

        assertNull(route.hop(HopRole.Entry))
        assertEquals(listOf(HopRole.Origin, HopRole.Exit), route.hops.map { it.role })
    }

    @Test
    fun `entry hop is reported as configured, never measured`() = runTest {
        val world = TestWorld(status = connectedStatus(exitIp = VPS_IP))

        val entry = world.resolve().hop(HopRole.Entry)

        assertEquals(ENTRY_HOST, entry?.ip)
        assertEquals(HopEvidence.Configured, entry?.evidence)
        assertTrue(entry?.via?.contains("wireguard") == true)
    }

    /** A timing taken over the direct path says nothing about the tunnelled one. */
    @Test
    fun `path time is dropped when the route changes under it`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))
        world.probe.next = PublicIpSample(ORIGIN_IP, elapsedMs = 30)
        assertEquals(30, world.resolve().throughRttMs)

        // Same instant, so the cached sample is still fresh — but the path changed.
        world.probe.next = null
        world.status.value = connectedStatus(exitIp = VPS_IP)

        assertNull(world.resolve().throughRttMs)
    }

    @Test
    fun `the probe is throttled between ticks and re-run once it goes stale`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))
        world.probe.next = PublicIpSample(ORIGIN_IP, elapsedMs = 30)

        world.resolve()
        world.resolve()
        assertEquals(1, world.probe.calls)

        world.clock += 10_001
        world.resolve()
        assertEquals(2, world.probe.calls)
    }

    /** The stall this exists to prevent: the periodic refresh used to cancel the
     *  probe in flight, so on a link slower than one tick — a tunnel still
     *  warming up — the route never resolved at all. */
    @Test
    fun `a probe slower than the refresh interval still resolves`() = runTest {
        val world = TestWorld(status = connectedStatus(exitIp = null))
        world.probe.next = PublicIpSample(VPS_IP, elapsedMs = 7_000)
        world.probe.takesMs = 7_000

        val exit = world.resolve().hop(HopRole.Exit)

        assertEquals(VPS_IP, exit?.ip)
        assertEquals(HopEvidence.Measured, exit?.evidence)
    }

    @Test
    fun `a failed probe keeps the last reading for the same path rather than blanking it`() = runTest {
        val world = TestWorld(status = ChainStatus(state = TunnelState.Disconnected))
        world.probe.next = PublicIpSample(ORIGIN_IP, elapsedMs = 30)
        world.resolve()

        world.clock += 10_001
        world.probe.next = null

        val origin = world.resolve().hop(HopRole.Origin)
        assertEquals(ORIGIN_IP, origin?.ip)
    }
}

private fun connectedStatus(exitIp: String?) =
    ChainStatus(state = TunnelState.Connected, exitIp = exitIp)

private fun profile(entry: WireGuardEntry? = defaultEntry()) = ChainProfile(
    vpsIp = VPS_IP,
    vlessUuid = "uuid",
    realityPublicKey = "pubkey",
    shortId = "shortid",
    entryHop = entry,
)

private fun defaultEntry() = WireGuardEntry(
    privateKey = "priv",
    address = "10.2.0.2/32",
    peerPublicKey = "peer",
    endpointHost = ENTRY_HOST,
)

/** The five collaborators the use case needs, wired to a controllable clock. */
private class TestWorld(
    status: ChainStatus,
    profile: ChainProfile? = profile(),
    lastOrigin: String? = null,
    settings: UserSettings = UserSettings(),
) {
    var clock = 1_000_000L
    val status = MutableStateFlow(status)
    val probe = FakeProbe()
    val origins = FakeOrigins(lastOrigin)

    private val useCase = ObserveChainRouteUseCase(
        profileRepository = FakeProfiles(profile),
        settingsRepository = FakeSettings(settings),
        chainRepository = FakeChain(this.status),
        originRepository = origins,
        geolocator = StubGeolocator,
        probe = probe,
        now = { clock },
    )

    suspend fun resolve(): ChainRoute = useCase().first()
}

private class FakeProbe : NetworkProbe {
    var next: PublicIpSample? = null
    var takesMs = 0L
    var calls = 0
    override suspend fun samplePublicIp(): PublicIpSample? {
        calls++
        if (takesMs > 0) delay(takesMs)
        return next
    }
}

private class FakeOrigins(initial: String?) : OriginRepository {
    var recorded: String? = null
    override val lastKnownOriginIp = MutableStateFlow(initial)
    override suspend fun record(ip: String) {
        recorded = ip
        lastKnownOriginIp.value = ip
    }
}

private class FakeProfiles(profile: ChainProfile?) : ProfileRepository {
    override val profile: Flow<ChainProfile?> = MutableStateFlow(profile)
    override val profiles: Flow<List<SavedProfile>> = MutableStateFlow(emptyList())
    override val activeProfileId: Flow<String?> = MutableStateFlow(null)
    override suspend fun save(profile: ChainProfile) = Unit
    override suspend fun add(name: String, profile: ChainProfile) = "id"
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setActive(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun importFromSecretsEnv(text: String) = Result.failure<ChainProfile>(
        UnsupportedOperationException("not used in these tests"),
    )
}

private class FakeSettings(settings: UserSettings) : SettingsRepository {
    override val settings = MutableStateFlow(settings)
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

private class FakeChain(override val status: Flow<ChainStatus>) : ChainRepository {
    override suspend fun connect() = Result.success(Unit)
    override suspend fun reconnect() = Result.success(Unit)
    override suspend fun disconnect() = Unit
    override suspend fun release() = Unit
    override val connectionIntent: Flow<Boolean> = MutableStateFlow(false)
    override val stats: Flow<SessionStats> = MutableStateFlow(SessionStats())
    override val killSwitchGuidanceSupported = false
    override val alwaysOnDetected: Flow<Boolean> = MutableStateFlow(false)
    override fun openSystemVpnSettings() = Unit
}

/** Resolves anything to one country: these tests are about the rules, not the map. */
private object StubGeolocator : RouteGeolocator {
    override suspend fun locate(hostOrIp: String) =
        HopLocation(countryCode = "IS", countryName = "Iceland", lat = 64.9, lon = -19.0)
}
