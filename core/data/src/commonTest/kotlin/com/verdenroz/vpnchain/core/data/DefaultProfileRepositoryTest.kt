package com.verdenroz.vpnchain.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ConnectionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultProfileRepositoryTest {

    @Test
    fun `a conf becomes a wireguard only connection and the active one`() = runTest {
        val repository = repository()
        repository.add("relay", RELAY, null)

        val result = repository.importWireGuardConf(CONF, intoActive = false)

        assertTrue(result.isSuccess)
        val profiles = repository.profiles.first()
        assertEquals(listOf(ConnectionKind.Relay, ConnectionKind.WireGuard), profiles.map { it.kind })
        assertEquals("203.0.113.9", profiles.last().name)
        assertEquals(profiles.last().id, repository.activeProfileId.first())
    }

    @Test
    fun `a conf can become the entry hop of the active relay`() = runTest {
        val repository = repository()
        repository.add("relay", RELAY, null)

        repository.importWireGuardConf(CONF, intoActive = true)

        val active = repository.profile.first()
        assertEquals(ConnectionKind.Chain, active?.kind)
        assertEquals("relay", active?.name)
        assertEquals("203.0.113.9", active?.wireGuard?.endpointHost)
        assertEquals(1, repository.profiles.first().size)
    }

    @Test
    fun `an entry hop import with no relay falls back to a new connection`() = runTest {
        val repository = repository()

        repository.importWireGuardConf(CONF, intoActive = true)

        assertEquals(ConnectionKind.WireGuard, repository.profile.first()?.kind)
    }

    @Test
    fun `saving a relay cannot attach it to a wireguard only profile`() = runTest {
        val repository = repository()
        repository.importWireGuardConf(CONF, intoActive = false)
        val wireGuardId = repository.activeProfileId.first()

        repository.save(RELAY, null)

        val profiles = repository.profiles.first()
        val original = profiles.single { it.id == wireGuardId }
        assertEquals(ConnectionKind.WireGuard, original.kind)
        assertNull(original.relay)
        assertEquals(listOf(ConnectionKind.WireGuard, ConnectionKind.Relay), profiles.map { it.kind })
    }

    @Test
    fun `importing a relay leaves a wireguard only profile intact`() = runTest {
        val repository = repository()
        repository.importWireGuardConf(CONF, intoActive = false)

        repository.importFromSecretsEnv("VPS_IP=198.51.100.1\nVLESS_UUID=u\nREALITY_PUBKEY=k\nSHORT_ID=ab")

        assertEquals(listOf(ConnectionKind.WireGuard, ConnectionKind.Relay), repository.profiles.first().map { it.kind })
    }

    @Test
    fun `a bad conf changes nothing`() = runTest {
        val repository = repository()

        val result = repository.importWireGuardConf("[Interface]\nAddress = 10.2.0.2/32", intoActive = false)

        assertEquals("Config has no peer.", result.exceptionOrNull()?.message)
        assertNull(repository.profile.first())
    }

    private fun repository(): ProfileRepository {
        var next = 0
        return DefaultProfileRepository(
            VpnChainPreferencesDataSource(MemoryPreferences()),
            newId = { "id${next++}" },
        )
    }
}

private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

private val RELAY = ChainProfile(
    vpsIp = "198.51.100.1",
    vlessUuid = "uuid",
    realityPublicKey = "key",
    shortId = "ab",
)

private val CONF = """
    [Interface]
    PrivateKey = PRIVATEKEYPLACEHOLDERPRIVATEKEYPLACEHOLDER1=
    Address = 10.2.0.2/32

    [Peer]
    PublicKey = cHVibGljIGtleSBwbGFjZWhvbGRlciBmb3IgdGVzdHM=
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.9:51820
""".trimIndent()
