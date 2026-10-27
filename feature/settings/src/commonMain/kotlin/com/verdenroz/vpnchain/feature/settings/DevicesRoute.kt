package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.feature.settings.component.DevicesActions
import com.verdenroz.vpnchain.feature.settings.component.DevicesSection
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DevicesRoute(modifier: Modifier = Modifier, viewModel: DevicesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        ),
        modifier = modifier,
    )
}
