package com.verdenroz.vpnchain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.app.generated.resources.Res
import com.verdenroz.vpnchain.app.generated.resources.update_allow_installs
import com.verdenroz.vpnchain.app.generated.resources.update_available
import com.verdenroz.vpnchain.app.generated.resources.update_corrupt
import com.verdenroz.vpnchain.app.generated.resources.update_dismiss
import com.verdenroz.vpnchain.app.generated.resources.update_downloading
import com.verdenroz.vpnchain.app.generated.resources.update_failed_install
import com.verdenroz.vpnchain.app.generated.resources.update_failed_network
import com.verdenroz.vpnchain.app.generated.resources.update_install
import com.verdenroz.vpnchain.app.generated.resources.update_installing
import com.verdenroz.vpnchain.app.generated.resources.update_no_build
import com.verdenroz.vpnchain.app.generated.resources.update_open_settings
import com.verdenroz.vpnchain.app.generated.resources.update_restart
import com.verdenroz.vpnchain.app.generated.resources.update_retry
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.update.AppUpdateState
import com.verdenroz.vpnchain.core.update.AppUpdates
import com.verdenroz.vpnchain.core.update.UpdateFailure
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Offers an available update and follows it through download, install and retry. Shows nothing otherwise. */
@Composable
fun UpdateBanner(updates: AppUpdates = koinInject()) {
    val state by updates.banner.collectAsStateWithLifecycle(null)
    state?.let { UpdateBannerBar(it, onAction = updates::update, onDismiss = updates::dismiss) }
}

@Composable
private fun UpdateBannerBar(
    state: AppUpdateState,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PanelTheme.colors
    val action = state.actionLabel()
    val busy = state is AppUpdateState.Downloading || state is AppUpdateState.Installing
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.shellRaised)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                state.text(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.readout,
            )
            action?.let { PanelButton(stringResource(it), onClick = onAction, prominent = true) }
            if (!busy) PanelButton(stringResource(Res.string.update_dismiss), onClick = onDismiss)
        }
        if (state is AppUpdateState.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = colors.lampGreen,
                trackColor = colors.shellDeep,
            )
        }
    }
}

@Composable
private fun AppUpdateState.text(): String =
    when (this) {
        is AppUpdateState.Available -> stringResource(Res.string.update_available, release.versionName)
        is AppUpdateState.Downloading -> stringResource(Res.string.update_downloading, (progress * 100).toInt())
        is AppUpdateState.Installing -> stringResource(Res.string.update_installing)
        is AppUpdateState.NeedsPermission -> stringResource(Res.string.update_allow_installs)
        is AppUpdateState.Ready -> stringResource(Res.string.update_restart)
        is AppUpdateState.Failed -> detail ?: stringResource(failure.messageRes())
        AppUpdateState.UpToDate -> ""
    }

private fun AppUpdateState.actionLabel(): StringResource? =
    when (this) {
        is AppUpdateState.Available -> Res.string.update_install
        is AppUpdateState.NeedsPermission -> Res.string.update_open_settings
        is AppUpdateState.Failed -> Res.string.update_retry
        else -> null
    }

private fun UpdateFailure.messageRes(): StringResource =
    when (this) {
        UpdateFailure.NoBuild -> Res.string.update_no_build
        UpdateFailure.Network -> Res.string.update_failed_network
        UpdateFailure.Corrupt -> Res.string.update_corrupt
        UpdateFailure.Install -> Res.string.update_failed_install
    }
