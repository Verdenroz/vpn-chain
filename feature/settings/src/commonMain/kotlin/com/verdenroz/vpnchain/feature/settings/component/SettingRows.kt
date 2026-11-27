package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.StatusDot
import com.verdenroz.vpnchain.core.designsystem.component.VpnSwitch
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.feature.settings.component.WarpModePicker
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_always_on_active
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_always_on_not_armed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_fail_open_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learn_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_warp_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_warp_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WarpSection(
    mode: WarpMode,
    learn: Boolean,
    failOpen: Boolean,
    onSetMode: (WarpMode) -> Unit,
    onToggleLearn: (Boolean) -> Unit,
    onToggleFailOpen: (Boolean) -> Unit,
) {
    val colors = VpnChainTheme.colors
    Column {
        Text(
            stringResource(Res.string.settings_warp_title),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(Res.string.settings_warp_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        WarpModePicker(selected = mode, onSelect = onSetMode)

        Reveal(visible = mode == WarpMode.Automatic) {
            Column {
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    title = stringResource(Res.string.settings_learn_title),
                    detail = null,
                    checked = learn,
                    onCheckedChange = onToggleLearn,
                )
                Spacer(Modifier.height(16.dp))
                SettingRow(
                    title = stringResource(Res.string.settings_fail_open_title),
                    detail = null,
                    checked = failOpen,
                    onCheckedChange = onToggleFailOpen,
                )
            }
        }
    }
}

@Composable
internal fun SettingRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = VpnChainTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            if (detail != null) {
                Spacer(Modifier.height(5.dp))
                SelectionContainer {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                }
            }
        }
        VpnSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun AlwaysOnGuidance(detected: Boolean, onOpenSystemVpnSettings: () -> Unit) {
    val colors = VpnChainTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (detected) colors.connectedText else colors.rerouted)
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                if (detected) {
                    stringResource(Res.string.settings_always_on_active)
                } else {
                    stringResource(Res.string.settings_always_on_not_armed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (detected) colors.ink else colors.inkMuted,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
        }
        // Android's own setting, not something this app can flip directly — the
        // toggle mirrors real status, and either direction opens system VPN
        // settings rather than changing anything here. Always visible (not just
        // while unarmed) so there's a way back to turn it off too.
        VpnSwitch(checked = detected, onCheckedChange = { onOpenSystemVpnSettings() })
    }
}

@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column { content() }
    }
}
