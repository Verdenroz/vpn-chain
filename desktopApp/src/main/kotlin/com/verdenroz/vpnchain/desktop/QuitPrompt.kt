package com.verdenroz.vpnchain.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.ui.shouldUseDarkTheme
import com.verdenroz.vpnchain.desktop.generated.resources.Res
import com.verdenroz.vpnchain.desktop.generated.resources.quit_blocked_message
import com.verdenroz.vpnchain.desktop.generated.resources.quit_blocked_title
import com.verdenroz.vpnchain.desktop.generated.resources.quit_blocked_unblock
import com.verdenroz.vpnchain.desktop.generated.resources.tray_cancel
import com.verdenroz.vpnchain.desktop.generated.resources.tray_quit
import org.jetbrains.compose.resources.stringResource

/**
 * Asked before quitting with the kill switch still loaded: once the app is
 * gone, nothing on screen explains the dead network or offers to lift it.
 */
@Composable
internal fun QuitPrompt(
    themeConfig: ThemeConfig,
    onUnblockAndQuit: () -> Unit,
    onQuit: () -> Unit,
    onStay: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onStay,
        state = rememberDialogState(size = DpSize(380.dp, 270.dp)),
        title = stringResource(Res.string.quit_blocked_title),
        resizable = false,
    ) {
        VpnChainTheme(darkTheme = shouldUseDarkTheme(themeConfig)) {
            Column(
                Modifier.fillMaxSize().background(VpnChainTheme.colors.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(Res.string.quit_blocked_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VpnChainTheme.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    stringResource(Res.string.quit_blocked_unblock),
                    onClick = onUnblockAndQuit,
                    modifier = Modifier.fillMaxWidth(),
                    tone = ActionTone.Primary,
                )
                ActionButton(
                    stringResource(Res.string.tray_quit),
                    onClick = onQuit,
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionButton(
                    stringResource(Res.string.tray_cancel),
                    onClick = onStay,
                    modifier = Modifier.fillMaxWidth(),
                    tone = ActionTone.Plain,
                )
            }
        }
    }
}
