package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.domain.ChangeHopsUseCase
import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_file_too_large
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_file_unreadable
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_same_as_entry
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_same_as_exit
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

private const val PRIVATE_KEY = "kPwG0r8sXv1zB3dF5hJ7lN9pR2tV4xZ6bD8fH0jL2nM="
private const val OTHER_KEY = "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI="

private fun conf(key: String = PRIVATE_KEY) = """
    [Interface]
    PrivateKey = $key
    Address = 10.2.0.2/32
    DNS = 10.2.0.1

    [Peer]
    PublicKey = pEeRpUbLiCkEyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
    AllowedIPs = 0.0.0.0/0
    Endpoint = 203.0.113.9:51820
""".trimIndent()

@OptIn(ExperimentalCoroutinesApi::class)
class HopsViewModelTest {

    private val log = mutableListOf<String>()
    private val profiles = FakeProfiles(log)
    private val settings = FakeSettings()
    private val warp = FakeWarp()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(state: TunnelState = TunnelState.Connected): HopsViewModel {
        val chain = FakeChain(state, log)
        return HopsViewModel(profiles, settings, warp, ChangeHopsUseCase(profiles, chain), ChangeRoutingUseCase(settings, chain))
    }

    @Test
    fun `a pasted conf is reviewed by its endpoint before anything is saved`() {
        val model = viewModel()

        model.review(HopCard.Entry, conf())

        val step = assertIs<HopStep.Confirm>(model.state.value.steps[HopCard.Entry])
        assertEquals("203.0.113.9", step.entry.endpointHost)
        assertEquals(emptyList(), log)
    }

    @Test
    fun `confirming saves the hop, moves a live tunnel and closes the step`() {
        val model = viewModel()
        model.review(HopCard.Exit, conf())

        model.confirm(HopCard.Exit)

        assertEquals(listOf("exit:set", "restart"), log)
        assertNull(model.state.value.steps[HopCard.Exit])
        assertEquals("203.0.113.9", model.state.value.deviceExit?.endpointHost)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a stopped tunnel is left alone`() {
        val model = viewModel(TunnelState.Disconnected)
        model.review(HopCard.Entry, conf())

        model.confirm(HopCard.Entry)

        assertEquals(listOf("entry:set"), log)
    }

    @Test
    fun `removing a hop asks first and then clears it`() {
        val model = viewModel()
        model.review(HopCard.Entry, conf())
        model.confirm(HopCard.Entry)
        log.clear()

        model.askRemove(HopCard.Entry)
        assertEquals(HopStep.ConfirmRemove, model.state.value.steps[HopCard.Entry])
        assertEquals(emptyList(), log)
        model.confirm(HopCard.Entry)

        assertEquals(listOf("entry:clear", "restart"), log)
        assertNull(model.state.value.entry)
    }

    @Test
    fun `cancelling a review drops the conf`() {
        val model = viewModel()
        model.review(HopCard.Exit, conf())

        model.dismiss(HopCard.Exit)

        assertNull(model.state.value.steps[HopCard.Exit])
        assertFalse(PRIVATE_KEY in model.state.value.toString())
        assertEquals(emptyList(), log)
    }

    @Test
    fun `no step prints a key`() {
        val model = viewModel()
        model.review(HopCard.Entry, conf())

        assertFalse(PRIVATE_KEY in model.state.value.steps.toString())
        assertFalse(PRIVATE_KEY in model.state.value.toString())
    }

    @Test
    fun `an unparseable conf is refused in place`() {
        val model = viewModel()

        model.review(HopCard.Entry, "[Interface]")

        assertIs<HopStep.Pasting>(model.state.value.steps[HopCard.Entry])
        assertEquals(emptyList(), log)
    }

    @Test
    fun `a file that is too big or unreadable is shown where the paste box is`() {
        val model = viewModel()

        model.picked(HopCard.Entry, PickedConf.TooLarge)
        assertEquals(UiText.Resource(Res.string.hops_file_too_large), assertIs<HopStep.Pasting>(model.state.value.steps[HopCard.Entry]).error)

        model.picked(HopCard.Entry, PickedConf.Unreadable)
        assertEquals(UiText.Resource(Res.string.hops_file_unreadable), assertIs<HopStep.Pasting>(model.state.value.steps[HopCard.Entry]).error)
    }

    @Test
    fun `a chosen file is reviewed like a paste and a cancelled dialog does nothing`() {
        val model = viewModel()

        model.picked(HopCard.Exit, null)
        assertNull(model.state.value.steps[HopCard.Exit])

        model.picked(HopCard.Exit, PickedConf.Text(conf()))
        assertIs<HopStep.Confirm>(model.state.value.steps[HopCard.Exit])
    }

    @Test
    fun `the exit refuses the entry hop's key and the entry hop refuses the exit's`() {
        val model = viewModel()
        model.review(HopCard.Entry, conf())
        model.confirm(HopCard.Entry)

        model.review(HopCard.Exit, conf())
        assertEquals(UiText.Resource(Res.string.hops_same_as_entry), assertIs<HopStep.Pasting>(model.state.value.steps[HopCard.Exit]).error)

        model.review(HopCard.Exit, conf(OTHER_KEY))
        model.confirm(HopCard.Exit)
        model.review(HopCard.Entry, conf(OTHER_KEY))
        assertEquals(UiText.Resource(Res.string.hops_same_as_exit), assertIs<HopStep.Pasting>(model.state.value.steps[HopCard.Entry]).error)
    }

    @Test
    fun `turning the entry hop off moves a live tunnel without stopping it`() {
        viewModel().setEntryHopEnabled(false)

        assertEquals(listOf("restart"), log)
        assertEquals(false, settings.settings.value.entryHopEnabled)
    }

    @Test
    fun `warp carries all traffic only with a registered tail`() {
        settings.settings.value = settings.settings.value.copy(warpMode = WarpMode.AllTraffic)
        assertFalse(viewModel().state.value.warpCarriesAll)

        warp.registered.value = true
        assertEquals(true, viewModel().state.value.warpCarriesAll)
    }

    @Test
    fun `the state carries what the cards need to say`() {
        settings.settings.value = settings.settings.value.copy(systemWideTun = false)

        val state = viewModel().state.value

        assertFalse(state.entryDialled)
        assertFalse(state.warpCarriesAll)
    }
}
