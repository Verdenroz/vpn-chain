package com.verdenroz.vpnchain.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.common.Platform
import com.verdenroz.vpnchain.core.common.currentPlatform
import com.verdenroz.vpnchain.core.config.SecretsEnvParser
import com.verdenroz.vpnchain.core.designsystem.component.IndicatorLamp
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.component.PanelField
import com.verdenroz.vpnchain.core.designsystem.component.PanelToggle
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.ui.ScreenNameplate
import com.verdenroz.vpnchain.core.ui.SectionCard
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.component.ProfileForm
import com.verdenroz.vpnchain.feature.settings.component.ThemePicker
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_always_on_active
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_always_on_not_armed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_appearance_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_chain_identity_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_configured_for
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_edit_fields
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_enter_manually
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_fail_closed_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_fail_closed_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_hide_fields
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_hide_import
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_hide_qr
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_from_disk
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_hint_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_hint_desktop
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_pasted
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_kill_switch_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_no_profile_yet
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_qr_warning
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_route_entire_system_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_route_entire_system_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_routing_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_scan_qr
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_secrets_env_label
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_show_qr
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Resolved here, in the Composable body, since asString() needs a
    // stringResource context that the coroutine scope below doesn't have.
    val messageText = message?.let {
        when (it) {
            is SettingsMessage.Info -> it.text
            is SettingsMessage.Error -> it.text
        }
    }?.asString()

    LaunchedEffect(message) {
        messageText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    SettingsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onSelectTheme = viewModel::setThemeConfig,
        onSaveProfile = viewModel::saveProfile,
        onClearProfile = viewModel::clearProfile,
        onImportText = viewModel::importSecretsEnv,
        onImportDefault = viewModel::importDefaultSecretsEnv,
        onToggleSystemWide = viewModel::setSystemWideTun,
        onToggleKillSwitch = viewModel::setKillSwitchEnabled,
        onOpenSystemVpnSettings = viewModel::openSystemVpnSettings,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onSelectTheme: (ThemeConfig) -> Unit,
    onSaveProfile: (ChainProfile) -> Unit,
    onClearProfile: () -> Unit,
    onImportText: (String) -> Unit,
    onImportDefault: () -> Unit,
    onToggleSystemWide: (Boolean) -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    val hasProfile = uiState.profile != null
    // Import is the primary path for a fresh install; once a profile exists it
    // collapses back to a summary so re-importing is a deliberate action.
    var importExpanded by remember(hasProfile) { mutableStateOf(!hasProfile) }
    var formExpanded by remember { mutableStateOf(false) }
    var qrExpanded by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.shellDeep)
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        ScreenNameplate(title = stringResource(Res.string.settings_title)) {
            IndicatorLamp(color = colors.lampWhite, lit = hasProfile, size = 9.dp)
        }

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionCard(title = stringResource(Res.string.settings_chain_identity_title)) {
                Text(
                    if (hasProfile) {
                        stringResource(Res.string.settings_configured_for, uiState.profile?.vpsIp ?: "")
                    } else {
                        stringResource(Res.string.settings_no_profile_yet)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasProfile) colors.readout else colors.muted,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton(
                        text = if (importExpanded) {
                            stringResource(Res.string.settings_hide_import)
                        } else {
                            stringResource(Res.string.settings_import)
                        },
                        onClick = { importExpanded = !importExpanded },
                        prominent = !hasProfile,
                    )
                    PanelButton(
                        text = when {
                            formExpanded -> stringResource(Res.string.settings_hide_fields)
                            hasProfile -> stringResource(Res.string.settings_edit_fields)
                            else -> stringResource(Res.string.settings_enter_manually)
                        },
                        onClick = { formExpanded = !formExpanded },
                    )
                    // Pairing source, not a sink — only worth offering once there's a
                    // profile to show, and only desktop can render one.
                    if (hasProfile && currentPlatform == Platform.Desktop) {
                        PanelButton(
                            text = if (qrExpanded) {
                                stringResource(Res.string.settings_hide_qr)
                            } else {
                                stringResource(Res.string.settings_show_qr)
                            },
                            onClick = { qrExpanded = !qrExpanded },
                        )
                    }
                }

                Reveal(visible = importExpanded) {
                    Spacer(Modifier.height(16.dp))
                    ImportSection(onImportText = onImportText, onImportDefault = onImportDefault)
                }
                Reveal(visible = formExpanded) {
                    Spacer(Modifier.height(16.dp))
                    ProfileForm(
                        initial = uiState.profile,
                        onSave = onSaveProfile,
                        onClear = onClearProfile,
                    )
                }
                Reveal(visible = qrExpanded && uiState.profile != null) {
                    Spacer(Modifier.height(16.dp))
                    uiState.profile?.let { QrPairingPanel(it) }
                }
            }

            // Android has no proxy-only mode — the tunnel is always TUN — so this
            // toggle would be a no-op there.
            if (currentPlatform == Platform.Desktop) {
                SectionCard(title = stringResource(Res.string.settings_routing_title)) {
                    SettingRow(
                        title = stringResource(Res.string.settings_route_entire_system_title),
                        detail = stringResource(Res.string.settings_route_entire_system_detail),
                        checked = uiState.settings.systemWideTun,
                        onCheckedChange = onToggleSystemWide,
                    )
                }
            }

            SectionCard(title = stringResource(Res.string.settings_kill_switch_title)) {
                // killSwitchEnabled/nftables is a desktop-only mechanism; Android's
                // fail-closed path is entirely the system Always-on VPN toggle below.
                if (!uiState.killSwitchGuidanceSupported) {
                    SettingRow(
                        title = stringResource(Res.string.settings_fail_closed_title),
                        detail = stringResource(Res.string.settings_fail_closed_detail),
                        checked = uiState.settings.killSwitchEnabled,
                        onCheckedChange = onToggleKillSwitch,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (uiState.killSwitchGuidanceSupported) {
                    AlwaysOnGuidance(
                        detected = uiState.alwaysOnDetected,
                        onOpenSystemVpnSettings = onOpenSystemVpnSettings,
                    )
                }
            }

            SectionCard(title = stringResource(Res.string.settings_appearance_title)) {
                ThemePicker(selected = uiState.settings.themeConfig, onSelect = onSelectTheme)
            }
        }
        SnackbarHost(snackbarHostState)
    }
}

/** Label, rationale, and the switch that acts on it — the panel's one setting shape. */
@Composable
private fun SettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = PanelTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.readout)
            Spacer(Modifier.height(5.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        PanelToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AlwaysOnGuidance(detected: Boolean, onOpenSystemVpnSettings: () -> Unit) {
    val colors = PanelTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IndicatorLamp(
            color = if (detected) colors.lampGreen else colors.lampAmber,
            lit = true,
            size = 8.dp,
        )
        Text(
            if (detected) {
                stringResource(Res.string.settings_always_on_active)
            } else {
                stringResource(Res.string.settings_always_on_not_armed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (detected) colors.readout else colors.muted,
            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
        )
        // Android's own setting, not something this app can flip directly — the
        // toggle mirrors real status, and either direction opens system VPN
        // settings rather than changing anything here. Always visible (not just
        // while unarmed) so there's a way back to turn it off too.
        PanelToggle(checked = detected, onCheckedChange = { onOpenSystemVpnSettings() })
    }
}

@Composable
private fun ImportSection(
    onImportText: (String) -> Unit,
    onImportDefault: () -> Unit,
) {
    val colors = PanelTheme.colors
    var pasted by remember { mutableStateOf("") }
    val scanQr = rememberQrScanLauncher { scanned -> scanned?.let(onImportText) }

    Column {
        // Typing 12 secret fields on a phone keyboard is miserable — scanning the
        // desktop app's QR is the path Android users actually want.
        if (currentPlatform == Platform.Android) {
            PanelButton(
                text = stringResource(Res.string.settings_scan_qr),
                onClick = scanQr,
                prominent = true,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            if (currentPlatform == Platform.Android) {
                stringResource(Res.string.settings_import_hint_android)
            } else {
                stringResource(Res.string.settings_import_hint_desktop)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        Spacer(Modifier.height(12.dp))
        PanelField(
            label = stringResource(Res.string.settings_secrets_env_label),
            value = pasted,
            minLines = 4,
            onValueChange = { pasted = it },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(
                text = stringResource(Res.string.settings_import_pasted),
                onClick = { onImportText(pasted) },
                enabled = pasted.isNotBlank(),
                prominent = currentPlatform == Platform.Desktop,
            )
            if (currentPlatform == Platform.Desktop) {
                PanelButton(text = stringResource(Res.string.settings_import_from_disk), onClick = onImportDefault)
            }
        }
    }
}

/** Renders the current profile as a scannable QR — desktop side of device pairing. */
@Composable
private fun QrPairingPanel(profile: ChainProfile) {
    val colors = PanelTheme.colors
    val bitmap = remember(profile) { renderQrCode(SecretsEnvParser.format(profile)) }
    Column {
        Text(
            stringResource(Res.string.settings_qr_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.lampAmber,
        )
        Spacer(Modifier.height(12.dp))
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                // Nearest-neighbor, not the default bilinear: the QR is a phone
                // camera's target, so blurred module edges hurt scan reliability.
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(280.dp),
            )
        }
    }
}

@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column { content() }
    }
}
