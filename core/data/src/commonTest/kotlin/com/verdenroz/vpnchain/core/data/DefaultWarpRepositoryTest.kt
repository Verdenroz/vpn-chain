package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.warp.WarpRegistrar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private fun exit(privateKey: String, registeredAt: Long) = WarpExit(
    privateKey = privateKey,
    addressV4 = "172.16.0.2/32",
    addressV6 = "2606:4700::2/128",
    peerPublicKey = "peer",
    registeredAtMillis = registeredAt,
)

class DefaultWarpRepositoryTest {

    @Test
    fun `registers once and reuses the stored credentials`() = runTest {
        val world = WarpWorld(now = 1_000L)

        val first = world.repository.exit()
        val second = world.repository.exit()

        assertEquals("fresh", first?.privateKey)
        assertEquals("fresh", second?.privateKey)
        assertEquals(1, world.registrar.calls)
    }

    @Test
    fun `re-registers once the stored credentials go stale`() = runTest {
        val world = WarpWorld(now = WarpExit.REFRESH_AFTER_MILLIS + 1)
        world.preferences.setWarpExit(exit("old", registeredAt = 0L))

        val refreshed = world.repository.exit()

        assertEquals("fresh", refreshed?.privateKey)
        assertEquals(1, world.registrar.calls)
        // Persisted, or every connect would register a new identity.
        assertEquals("fresh", world.preferences.warpExit.first()?.privateKey)
    }

    /**
     * Refresh runs a month before Cloudflare's own expiry, and the usual reason
     * it fails is a network that can't reach Cloudflare at all — which says
     * nothing about the key already held.
     */
    @Test
    fun `keeps the stored key when a refresh fails`() = runTest {
        val world = WarpWorld(now = WarpExit.REFRESH_AFTER_MILLIS + 1, registrationFails = true)
        world.preferences.setWarpExit(exit("old", registeredAt = 0L))

        assertEquals("old", world.repository.exit()?.privateKey)
    }

    /** A chain that can't register still has to come up — one hop shorter. */
    @Test
    fun `yields nothing when there is no key and registration fails`() = runTest {
        val world = WarpWorld(now = 0L, registrationFails = true)

        assertNull(world.repository.exit())
    }
}

private class WarpWorld(now: Long, registrationFails: Boolean = false) {
    val preferences = VpnChainPreferencesDataSource(InMemoryWarpPreferences())
    val registrar = FakeRegistrar(registrationFails)
    val repository: WarpRepository = DefaultWarpRepository(
        preferences = preferences,
        registrar = registrar,
        logger = QuietLogger(),
        now = { now },
    )
}

private class FakeRegistrar(private val fails: Boolean) : WarpRegistrar {
    var calls = 0

    override suspend fun register(nowMillis: Long): Result<WarpExit> {
        calls++
        return if (fails) {
            Result.failure(IllegalStateException("no answer"))
        } else {
            Result.success(exit("fresh", registeredAt = nowMillis))
        }
    }
}

private class InMemoryWarpPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

private class QuietLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
