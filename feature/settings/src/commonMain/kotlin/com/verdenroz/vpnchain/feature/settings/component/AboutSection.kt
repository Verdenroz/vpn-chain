package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.InfoRow
import com.verdenroz.vpnchain.core.ui.SectionCard
import com.verdenroz.vpnchain.core.update.AppUpdateState
import com.verdenroz.vpnchain.core.update.AppUpdates
import com.verdenroz.vpnchain.core.update.CheckStatus
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

/** Shows the installed version, plus the latest one and a check button where this copy can update itself. */
@Composable
fun AboutSection(updates: AppUpdates = koinInject()) {
    val state by updates.state.collectAsStateWithLifecycle()
    val check by updates.check.collectAsStateWithLifecycle()
    SectionCard(title = stringResource(Res.string.settings_about_title)) {
        InfoRow(label = stringResource(Res.string.settings_about_version), value = updates.installedVersion)
        if (updates.supported) {
            InfoRow(label = stringResource(Res.string.settings_about_latest), value = latest(state, check))
            Spacer(Modifier.height(12.dp))
            ActionButton(
                stringResource(Res.string.settings_about_check_now),
                onClick = updates::checkNow,
                enabled = check != CheckStatus.Checking,
                compact = true,
            )
        }
    }
}

@Composable
private fun latest(state: AppUpdateState, check: CheckStatus): String =
    state.release?.versionName ?: when (check) {
        CheckStatus.Checking -> stringResource(Res.string.settings_about_checking)
        CheckStatus.Failed -> stringResource(Res.string.settings_about_check_failed)
        CheckStatus.Done -> stringResource(Res.string.settings_about_up_to_date)
    }
