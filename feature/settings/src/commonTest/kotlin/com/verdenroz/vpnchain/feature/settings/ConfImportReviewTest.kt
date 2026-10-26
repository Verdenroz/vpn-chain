package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConfImportReviewTest {

    private val log = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a pasted conf waits for review and changes nothing`() {
        val viewModel = viewModel(TunnelState.Connected)

        viewModel.importSecretsEnv(CONF)

        val pending = assertIs<PendingImport.Conf>(viewModel.pendingImport.value)
        assertEquals("198.51.100.7", pending.entry.endpointHost)
        assertEquals(51820, pending.entry.endpointPort)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `use it saves the conf as active and moves the live tunnel onto it`() {
        val viewModel = viewModel(TunnelState.Connected)
        viewModel.importSecretsEnv(CONF)

        viewModel.usePendingImport()

        assertEquals(listOf("import:false:true", "restart"), log)
        assertNull(viewModel.pendingImport.value)
    }

    @Test
    fun `an entry hop conf also waits for use it`() {
        val viewModel = viewModel(TunnelState.Connected)

        viewModel.importAsEntryHop(CONF)
        assertEquals(emptyList(), log)
        viewModel.usePendingImport()

        assertEquals(listOf("import:true:true", "restart"), log)
    }

    @Test
    fun `save keeps the conf without switching or reconnecting`() {
        val viewModel = viewModel(TunnelState.Connected)
        viewModel.importAsEntryHop(CONF)

        viewModel.savePendingImport()

        assertEquals(listOf("import:false:false"), log)
        assertIs<SettingsMessage.Info>(viewModel.message.value)
    }

    @Test
    fun `cancel drops the conf`() {
        val viewModel = viewModel(TunnelState.Connected)
        viewModel.importSecretsEnv(CONF)

        viewModel.cancelPendingImport()
        viewModel.usePendingImport()

        assertNull(viewModel.pendingImport.value)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `a parsed conf clears the paste field and a bad one does not`() {
        val viewModel = viewModel(TunnelState.Disconnected)

        viewModel.importSecretsEnv("[Interface]\nAddress = 10.2.0.2/32")
        assertEquals(0, viewModel.importsTaken.value)
        assertIs<SettingsMessage.Error>(viewModel.message.value)

        viewModel.importSecretsEnv(CONF)
        assertEquals(1, viewModel.importsTaken.value)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a pasted secrets env waits for review and changes nothing`() {
        val viewModel = viewModel(TunnelState.Connected)

        viewModel.importSecretsEnv(SECRETS)

        val pending = assertIs<PendingImport.SecretsEnv>(viewModel.pendingImport.value)
        assertEquals("203.0.113.10", pending.relay.vpsIp)
        assertNull(pending.entryHop)
        assertEquals(1, viewModel.importsTaken.value)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `use it makes a reviewed secrets env the active connection`() {
        val viewModel = viewModel(TunnelState.Disconnected)
        viewModel.importSecretsEnv(SECRETS)

        viewModel.usePendingImport()

        assertEquals(listOf("secrets:true"), log)
        assertNull(viewModel.pendingImport.value)
    }

    @Test
    fun `save keeps a reviewed secrets env without switching`() {
        val viewModel = viewModel(TunnelState.Connected)
        viewModel.importSecretsEnv(SECRETS)

        viewModel.savePendingImport()

        assertEquals(listOf("secrets:false"), log)
    }

    @Test
    fun `cancel drops a reviewed secrets env`() {
        val viewModel = viewModel(TunnelState.Connected)
        viewModel.importSecretsEnv(SECRETS)

        viewModel.cancelPendingImport()

        assertNull(viewModel.pendingImport.value)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `a pending import keeps the pasted keys out of its string`() {
        val viewModel = viewModel(TunnelState.Disconnected)

        viewModel.importSecretsEnv(CONF)

        assertFalse("PRIVATEKEYPLACEHOLDER" in viewModel.pendingImport.value.toString())
    }

    private fun viewModel(state: TunnelState): SettingsViewModel {
        val profiles = FakeProfiles(log)
        val chain = FakeChain(state, log)
        return SettingsViewModel(
            profileRepository = profiles,
            settingsRepository = FakeSettings(),
            importProfile = ImportProfileUseCase(profiles),
            importWireGuardConf = ImportWireGuardConfUseCase(profiles, chain),
            switchProfile = SwitchProfileUseCase(profiles, chain),
            chainRepository = chain,
        )
    }
}

private const val SECRETS = "VPS_IP=203.0.113.10\nVLESS_UUID=u\nREALITY_PUBKEY=k\nSHORT_ID=ab"

private val CONF = """
    [Interface]
    PrivateKey = PRIVATEKEYPLACEHOLDERPRIVATEKEYPLACEHOLDER1=
    Address = 10.2.0.2/32

    [Peer]
    PublicKey = cHVibGljIGtleSBwbGFjZWhvbGRlciBmb3IgdGVzdHM=
    AllowedIPs = 0.0.0.0/0
    Endpoint = 198.51.100.7:51820
""".trimIndent()
