package com.verdenroz.vpnchain.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.common.Platform
import com.verdenroz.vpnchain.core.common.currentPlatform
import com.verdenroz.vpnchain.core.common.systemTrayAvailable
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ConnectionKind
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.ui.ScreenTitle
import com.verdenroz.vpnchain.core.ui.SectionCard
import com.verdenroz.vpnchain.core.ui.asString
import com.verdenroz.vpnchain.feature.settings.component.AboutSection
import com.verdenroz.vpnchain.feature.settings.component.AlwaysOnGuidance
import com.verdenroz.vpnchain.feature.settings.component.ImportReview
import com.verdenroz.vpnchain.feature.settings.component.ImportSection
import com.verdenroz.vpnchain.feature.settings.component.QrPairingPanel
import com.verdenroz.vpnchain.feature.settings.component.Reveal
import com.verdenroz.vpnchain.feature.settings.component.SettingRow
import com.verdenroz.vpnchain.feature.settings.component.WarpSection
import com.verdenroz.vpnchain.feature.settings.component.DnsFilterPicker
import com.verdenroz.vpnchain.feature.settings.component.PairingStatusPanel
import com.verdenroz.vpnchain.feature.settings.component.ProfileForm
import com.verdenroz.vpnchain.feature.settings.component.ProfilePicker
import com.verdenroz.vpnchain.feature.settings.component.ThemePicker
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.devices_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_allow_local_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_allow_local_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_appearance_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_connect_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_reconnect_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_reconnect_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_start_detail_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_start_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_start_title_android
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_automation_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_chain_identity_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_close_to_tray_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_close_to_tray_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_configured_for
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_connection_log_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_connection_log_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_diagnostics_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_dns_filter_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_dns_filter_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_dns_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_edit_fields
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_enter_manually
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_entry_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_exit_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_fail_closed_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_fail_closed_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_hide_fields
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_hide_import
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_hide_qr
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_delete
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_wireguard_summary
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_kill_switch_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_no_profile_yet
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profiles_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_route_entire_system_detail
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_route_entire_system_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_routing_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_show_qr
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_window_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val importsTaken by viewModel.importsTaken.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Hoisted above SettingsScreen so an external QrScanRequest (widget/deep
    // link) can fire the same launcher the in-screen button uses.
    val scanQr = rememberQrScanLauncher { scanned -> scanned?.let(viewModel::importSecretsEnv) }
    val scanRequested by QrScanRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(scanRequested) {
        if (scanRequested) {
            QrScanRequest.consume()
            scanQr()
        }
    }

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
        onImportAsEntryHop = viewModel::importAsEntryHop,
        onImportDefault = viewModel::importDefaultSecretsEnv,
        onScanQr = scanQr,
        pendingImport = pendingImport,
        importsTaken = importsTaken,
        onUsePendingImport = viewModel::usePendingImport,
        onSavePendingImport = viewModel::savePendingImport,
        onCancelPendingImport = viewModel::cancelPendingImport,
        pairing = pairing,
        onDismissPairing = viewModel::dismissPairing,
        onToggleSystemWide = viewModel::setSystemWideTun,
        onToggleKillSwitch = viewModel::setKillSwitchEnabled,
        onToggleAllowLocalNetworks = viewModel::setAllowLocalNetworks,
        onSetDnsFilter = viewModel::setDnsFilter,
        onSetWarpMode = viewModel::setWarpMode,
        onToggleLearn = viewModel::setLearnBlockedSites,
        onToggleFailOpen = viewModel::setFailOpen,
        onToggleConnectionLog = viewModel::setConnectionLog,
        onToggleAutoConnect = viewModel::setAutoConnectOnLaunch,
        onToggleAutoReconnect = viewModel::setAutoReconnect,
        onToggleAutoStart = viewModel::setAutoStartOnLogin,
        onToggleCloseToTray = viewModel::setCloseToTray,
        onSelectProfile = viewModel::selectProfile,
        onDeleteProfile = viewModel::deleteProfile,
        onRenameProfile = viewModel::renameProfile,
        onOpenSystemVpnSettings = viewModel::openSystemVpnSettings,
        entryHop = { EntryHopRoute() },
        exitHop = { ExitHopRoute() },
        devices = { DevicesRoute() },
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
    onImportAsEntryHop: (String) -> Unit,
    onImportDefault: () -> Unit,
    onScanQr: () -> Unit,
    pendingImport: PendingImport?,
    importsTaken: Int,
    onUsePendingImport: () -> Unit,
    onSavePendingImport: () -> Unit,
    onCancelPendingImport: () -> Unit,
    pairing: PairingStatus?,
    onDismissPairing: () -> Unit,
    onToggleSystemWide: (Boolean) -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit,
    onToggleAllowLocalNetworks: (Boolean) -> Unit,
    onSetDnsFilter: (DnsFilter) -> Unit,
    onSetWarpMode: (WarpMode) -> Unit,
    onToggleLearn: (Boolean) -> Unit,
    onToggleFailOpen: (Boolean) -> Unit,
    onToggleConnectionLog: (Boolean) -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onToggleAutoStart: (Boolean) -> Unit,
    onToggleCloseToTray: (Boolean) -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    entryHop: @Composable () -> Unit,
    exitHop: @Composable () -> Unit,
    devices: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VpnChainTheme.colors
    val hasProfile = uiState.profile != null
    // Saving the relay form must never attach a relay to a WireGuard-only connection.
    val wireGuardOnly = uiState.profile?.kind == ConnectionKind.WireGuard
    // Import is the primary path for a fresh install; once a profile exists it
    // collapses back to a summary so re-importing is a deliberate action.
    var importExpanded by remember(hasProfile) { mutableStateOf(!hasProfile) }
    val importRequested by ImportRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(importRequested) {
        if (importRequested) {
            ImportRequest.consume()
            importExpanded = true
        }
    }
    var formExpanded by remember { mutableStateOf(false) }
    var qrExpanded by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        ScreenTitle(title = stringResource(Res.string.settings_title))

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (uiState.profiles.isNotEmpty()) {
                SectionCard(title = stringResource(Res.string.settings_profiles_title)) {
                    ProfilePicker(
                        profiles = uiState.profiles,
                        activeProfileId = uiState.activeProfileId,
                        onSelect = onSelectProfile,
                        onDelete = onDeleteProfile,
                        onRename = onRenameProfile,
                    )
                }
            }

            SectionCard(title = stringResource(Res.string.settings_chain_identity_title)) {
                Text(
                    if (wireGuardOnly) {
                        val profile = uiState.profile
                        stringResource(
                            Res.string.settings_wireguard_summary,
                            profile?.name.orEmpty(),
                            "${profile?.host}:${profile?.wireGuard?.endpointPort}",
                        )
                    } else if (hasProfile) {
                        stringResource(Res.string.settings_configured_for, uiState.profile?.host.orEmpty())
                    } else {
                        stringResource(Res.string.settings_no_profile_yet)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasProfile) colors.ink else colors.inkMuted,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        compact = true,
                        text = if (importExpanded) {
                            stringResource(Res.string.settings_hide_import)
                        } else {
                            stringResource(Res.string.settings_import)
                        },
                        onClick = { importExpanded = !importExpanded },
                        tone = if (hasProfile) ActionTone.Outlined else ActionTone.Primary,
                    )
                    if (wireGuardOnly) {
                        ActionButton(
                            compact = true,
                            text = stringResource(Res.string.settings_profile_delete),
                            onClick = { uiState.activeProfileId?.let(onDeleteProfile) },
                        )
                    } else {
                        ActionButton(
                            compact = true,
                            text = when {
                                formExpanded -> stringResource(Res.string.settings_hide_fields)
                                hasProfile -> stringResource(Res.string.settings_edit_fields)
                                else -> stringResource(Res.string.settings_enter_manually)
                            },
                            onClick = { formExpanded = !formExpanded },
                        )
                    }
                    // Pairing source, not a sink — only worth offering once there's a
                    // profile to show, and only desktop can render one.
                    if (hasProfile && !wireGuardOnly && currentPlatform == Platform.Desktop) {
                        ActionButton(
                            compact = true,
                            text = if (qrExpanded) {
                                stringResource(Res.string.settings_hide_qr)
                            } else {
                                stringResource(Res.string.settings_show_qr)
                            },
                            onClick = { qrExpanded = !qrExpanded },
                        )
                    }
                }

                Reveal(visible = pairing != null) {
                    pairing?.let {
                        Spacer(Modifier.height(16.dp))
                        PairingStatusPanel(it, onDismissPairing)
                    }
                }
                Reveal(visible = pendingImport != null) {
                    pendingImport?.let {
                        Spacer(Modifier.height(16.dp))
                        ImportReview(
                            pending = it,
                            activeName = uiState.profile?.name.orEmpty(),
                            canSaveOnly = hasProfile,
                            onUse = onUsePendingImport,
                            onSave = onSavePendingImport,
                            onCancel = onCancelPendingImport,
                        )
                    }
                }
                Reveal(visible = importExpanded) {
                    Spacer(Modifier.height(16.dp))
                    ImportSection(
                        clearKey = importsTaken,
                        onImportText = onImportText,
                        onImportAsEntryHop = onImportAsEntryHop.takeIf { uiState.profile?.relay != null },
                        onImportDefault = onImportDefault,
                        onScanQr = onScanQr,
                    )
                }
                Reveal(visible = formExpanded && !wireGuardOnly) {
                    Spacer(Modifier.height(16.dp))
                    ProfileForm(
                        initial = uiState.profile,
                        onSave = onSaveProfile,
                        onClear = onClearProfile,
                    )
                }
                Reveal(visible = qrExpanded && uiState.profile != null) {
                    Spacer(Modifier.height(16.dp))
                    uiState.profile?.let(::pairingPayload)?.let { QrPairingPanel(it) }
                }
            }

            if (uiState.profile?.relay != null) {
                SectionCard(title = stringResource(Res.string.hops_entry_title)) { entryHop() }
                SectionCard(title = stringResource(Res.string.hops_exit_title)) { exitHop() }
                SectionCard(title = stringResource(Res.string.devices_title)) { devices() }
            }

            SectionCard(title = stringResource(Res.string.settings_automation_title)) {
                SettingRow(
                    title = stringResource(Res.string.settings_auto_connect_title),
                    detail = null,
                    checked = uiState.settings.autoConnectOnLaunch,
                    onCheckedChange = onToggleAutoConnect,
                )
                Spacer(Modifier.height(16.dp))
                SettingRow(
                    title = stringResource(Res.string.settings_auto_reconnect_title),
                    detail = stringResource(Res.string.settings_auto_reconnect_detail),
                    checked = uiState.settings.autoReconnect,
                    onCheckedChange = onToggleAutoReconnect,
                )
                // Hidden rather than disabled where there is no login item to
                // write: an unpackaged run has nothing meaningful to register.
                if (uiState.autostartSupported) {
                    Spacer(Modifier.height(16.dp))
                    val onAndroid = currentPlatform == Platform.Android
                    SettingRow(
                        title = stringResource(
                            if (onAndroid) {
                                Res.string.settings_auto_start_title_android
                            } else {
                                Res.string.settings_auto_start_title
                            },
                        ),
                        detail = if (onAndroid) stringResource(Res.string.settings_auto_start_detail_android) else null,
                        checked = uiState.settings.autoStartOnLogin,
                        onCheckedChange = onToggleAutoStart,
                    )
                }
            }

            // Desktop-only, and only where a tray actually exists — many
            // Wayland bars speak SNI, which AWT cannot talk to.
            if (currentPlatform == Platform.Desktop && systemTrayAvailable) {
                SectionCard(title = stringResource(Res.string.settings_window_title)) {
                    SettingRow(
                        title = stringResource(Res.string.settings_close_to_tray_title),
                        detail = stringResource(Res.string.settings_close_to_tray_detail),
                        checked = uiState.settings.closeToTray,
                        onCheckedChange = onToggleCloseToTray,
                    )
                }
            }

            // Shown for every chain, even an Android one with no entry hop, for the WARP
            // tail. A bare WireGuard peer has no routing choices.
            if (!wireGuardOnly) {
                SectionCard(title = stringResource(Res.string.settings_routing_title)) {
                    // Android has no proxy-only mode — the tunnel is always TUN —
                    // so this toggle would be a no-op there.
                    if (currentPlatform == Platform.Desktop) {
                        SettingRow(
                            title = stringResource(Res.string.settings_route_entire_system_title),
                            detail = stringResource(Res.string.settings_route_entire_system_detail),
                            checked = uiState.settings.systemWideTun,
                            onCheckedChange = onToggleSystemWide,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    WarpSection(
                        mode = uiState.settings.warpMode,
                        learn = uiState.settings.learnBlockedSites,
                        failOpen = uiState.settings.failOpen,
                        onSetMode = onSetWarpMode,
                        onToggleLearn = onToggleLearn,
                        onToggleFailOpen = onToggleFailOpen,
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
                    SettingRow(
                        title = stringResource(Res.string.settings_allow_local_title),
                        detail = stringResource(Res.string.settings_allow_local_detail),
                        checked = uiState.settings.allowLocalNetworks,
                        onCheckedChange = onToggleAllowLocalNetworks,
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

            // The chain renders a resolver of its own only for a TUN chain;
            // desktop's proxy mode leaves DNS to the system, where this would
            // be a toggle that does nothing.
            if (currentPlatform == Platform.Android || uiState.settings.systemWideTun || wireGuardOnly) {
                SectionCard(title = stringResource(Res.string.settings_dns_title)) {
                    Text(
                        stringResource(Res.string.settings_dns_filter_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        stringResource(Res.string.settings_dns_filter_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    DnsFilterPicker(
                        selected = uiState.settings.dnsFilter,
                        onSelect = onSetDnsFilter,
                    )
                }
            }

            SectionCard(title = stringResource(Res.string.settings_diagnostics_title)) {
                SettingRow(
                    title = stringResource(Res.string.settings_connection_log_title),
                    detail = stringResource(Res.string.settings_connection_log_detail),
                    checked = uiState.settings.connectionLog,
                    onCheckedChange = onToggleConnectionLog,
                )
            }

            SectionCard(title = stringResource(Res.string.settings_appearance_title)) {
                ThemePicker(selected = uiState.settings.themeConfig, onSelect = onSelectTheme)
            }

            AboutSection()
        }
        SnackbarHost(snackbarHostState)
    }
}
