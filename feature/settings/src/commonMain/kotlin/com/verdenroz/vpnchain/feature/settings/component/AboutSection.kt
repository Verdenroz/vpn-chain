package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.ui.SectionCard
import com.verdenroz.vpnchain.core.update.AppUpdateState
import com.verdenroz.vpnchain.core.update.AppUpdates
import com.verdenroz.vpnchain.core.update.CheckStatus
import com.verdenroz.vpnchain.core.update.release
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_check_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_check_now
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_checking
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_latest
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_up_to_date
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_about_version
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The installed version, the newest known one and a way to look again; the check only shows where updating is possible. */
@Composable
fun AboutSection(updates: AppUpdates = koinInject()) {
    val state by updates.state.collectAsStateWithLifecycle()
    val check by updates.check.collectAsStateWithLifecycle()
    SectionCard(title = stringResource(Res.string.settings_about_title)) {
        AboutRow(stringResource(Res.string.settings_about_version), updates.installedVersion)
        if (updates.supported) {
            Spacer(Modifier.height(8.dp))
            AboutRow(stringResource(Res.string.settings_about_latest), latest(state, check))
            Spacer(Modifier.height(12.dp))
            PanelButton(
                stringResource(Res.string.settings_about_check_now),
                onClick = updates::checkNow,
                enabled = check != CheckStatus.Checking,
            )
        }
    }
}

@Composable
private fun latest(state: AppUpdateState, check: CheckStatus): String =
    state.release()?.versionName ?: when (check) {
        CheckStatus.Idle, CheckStatus.Checking -> stringResource(Res.string.settings_about_checking)
        CheckStatus.Failed -> stringResource(Res.string.settings_about_check_failed)
        CheckStatus.Done -> stringResource(Res.string.settings_about_up_to_date)
    }

@Composable
private fun AboutRow(label: String, value: String) {
    val colors = PanelTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.readout)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
}
