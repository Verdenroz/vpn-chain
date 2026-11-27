package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.feature.settings.component.EntryHopCard
import com.verdenroz.vpnchain.feature.settings.component.ExitActions
import com.verdenroz.vpnchain.feature.settings.component.ExitHopCard
import com.verdenroz.vpnchain.feature.settings.component.ExitState
import com.verdenroz.vpnchain.feature.settings.component.HopActions
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EntryHopRoute(
    modifier: Modifier = Modifier,
    viewModel: HopsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val choose = rememberConfFilePicker { viewModel.picked(HopCard.Entry, it) }
    EntryHopCard(
        state = state,
        actions = viewModel.actions(HopCard.Entry, choose),
        onToggleEnabled = viewModel::setEntryHopEnabled,
        modifier = modifier,
    )
}

@Composable
fun ExitHopRoute(
    modifier: Modifier = Modifier,
    viewModel: HopsViewModel = koinViewModel(),
    exitViewModel: ExitViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exitState by exitViewModel.state.collectAsStateWithLifecycle()
    val chooseForDevice = rememberConfFilePicker { viewModel.picked(HopCard.Exit, it) }
    val chooseForEveryDevice = rememberConfFilePicker(exitViewModel::picked)
    ExitHopCard(
        hops = state,
        device = viewModel.actions(HopCard.Exit, chooseForDevice),
        everyDevice = ExitState(
            state = exitState,
            actions = ExitActions(
                onStartChange = exitViewModel::startChange,
                onReview = exitViewModel::review,
                onPick = chooseForEveryDevice,
                onAskDirect = exitViewModel::askDirect,
                onConfirm = exitViewModel::confirm,
                onDismiss = exitViewModel::dismiss,
            ),
        ),
        modifier = modifier,
    )
}

private fun HopsViewModel.actions(card: HopCard, choose: () -> Unit) = HopActions(
    onStart = { startPaste(card) },
    onAskRemove = { askRemove(card) },
    onReview = { review(card, it) },
    onChoose = choose,
    onConfirm = { confirm(card) },
    onDismiss = { dismiss(card) },
)
