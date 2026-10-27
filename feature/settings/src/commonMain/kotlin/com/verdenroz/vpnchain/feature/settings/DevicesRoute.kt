package com.verdenroz.vpnchain.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.feature.settings.component.DevicesActions
import com.verdenroz.vpnchain.feature.settings.component.DevicesSection
import com.verdenroz.vpnchain.feature.settings.component.ExitActions
import com.verdenroz.vpnchain.feature.settings.component.ExitSection
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DevicesRoute(
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = koinViewModel(),
    exitViewModel: ExitViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exitState by exitViewModel.state.collectAsStateWithLifecycle()
    Column(modifier) {
        DevicesSection(
            state = state,
            actions = DevicesActions(
                onAdd = viewModel::add,
                onRename = viewModel::rename,
                onRestartAndAdd = viewModel::restartAndAdd,
                onCutOff = viewModel::cutOff,
                onNewCode = viewModel::newCode,
                onStartAdd = viewModel::startAdd,
                onStartRename = viewModel::startRename,
                onAskCutOff = viewModel::askCutOff,
                onAskNewCode = viewModel::askNewCode,
                onDismiss = viewModel::dismiss,
                onDismissNotice = viewModel::dismissNotice,
            ),
        )
        ExitSection(
            state = exitState,
            actions = ExitActions(
                onStartChange = exitViewModel::startChange,
                onReview = exitViewModel::review,
                onAskDirect = exitViewModel::askDirect,
                onConfirm = exitViewModel::confirm,
                onDismiss = exitViewModel::dismiss,
            ),
        )
    }
}
