package com.verdenroz.vpnchain.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.config.SecretsEnvParser
import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.PairingFailed
import com.verdenroz.vpnchain.core.domain.PairingStep
import com.verdenroz.vpnchain.core.domain.RelayPairing
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_start_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_saved
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_imported_not_connected
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_imported_profile
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_no_secrets_env_found
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_expired
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_no_answer
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_reconnect_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_unreachable
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_pairing_used
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_cleared
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_name_blank
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_renamed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_saved
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_switched
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_switch_failed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val profile: SavedProfile? = null,
    val killSwitchGuidanceSupported: Boolean = false,
    val alwaysOnDetected: Boolean = false,
    val autostartSupported: Boolean = false,
    val profiles: List<SavedProfile> = emptyList(),
    val activeProfileId: String? = null,
)

/**
 * A parsed import waiting for the user to look at where it connects. Nothing is
 * saved and nothing becomes active until they pick "Use it" or "Save".
 */
sealed interface PendingImport {
    val text: String

    /** A WireGuard `.conf`, as a connection of its own or as the active one's entry hop. */
    data class Conf(override val text: String, val entry: WireGuardEntry, val intoActive: Boolean) : PendingImport {
        override fun toString() = "PendingImport.Conf(entry=$entry, intoActive=$intoActive)"
    }

    /** A CLI `secrets.env`: a relay, and its entry hop when the file has one. */
    data class SecretsEnv(override val text: String, val relay: ChainProfile, val entryHop: WireGuardEntry?) : PendingImport {
        override fun toString() = "PendingImport.SecretsEnv(relay=$relay, entryHop=$entryHop)"
    }
}

/** Where pairing with a relay has got to. The host is the relay's address; nothing from the link is kept. */
sealed interface PairingStatus {
    data class Working(val step: PairingStep, val host: String) : PairingStatus
    data class Done(val host: String, val admin: Boolean) : PairingStatus
    data class Failed(val text: UiText) : PairingStatus
}

/** One-shot user feedback (import result), consumed by the UI. */
sealed interface SettingsMessage {
    data class Info(val text: UiText) : SettingsMessage
    data class Error(val text: UiText) : SettingsMessage
}

class SettingsViewModel(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val importProfile: ImportProfileUseCase,
    private val importWireGuardConf: ImportWireGuardConfUseCase,
    private val switchProfile: SwitchProfileUseCase,
    private val chainRepository: ChainRepository,
    private val changeRouting: ChangeRoutingUseCase,
    private val relayPairing: RelayPairing,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            profileRepository.profile,
            chainRepository.alwaysOnDetected,
            combine(profileRepository.profiles, profileRepository.activeProfileId, ::Pair),
        ) { settings, profile, alwaysOnDetected, (profiles, activeProfileId) ->
            SettingsUiState(
                settings = settings,
                profile = profile,
                killSwitchGuidanceSupported = chainRepository.killSwitchGuidanceSupported,
                alwaysOnDetected = alwaysOnDetected,
                autostartSupported = settingsRepository.autostartSupported,
                profiles = profiles,
                activeProfileId = activeProfileId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _pairing = MutableStateFlow<PairingStatus?>(null)
    val pairing: StateFlow<PairingStatus?> = _pairing.asStateFlow()
    private var pairingInFlight = false

    /** Bumped each time pasted text is taken, so the UI can clear the paste field. */
    private val _importsTaken = MutableStateFlow(0)
    val importsTaken: StateFlow<Int> = _importsTaken.asStateFlow()

    fun setThemeConfig(themeConfig: ThemeConfig) = viewModelScope.launch {
        settingsRepository.setThemeConfig(themeConfig)
    }

    fun setSystemWideTun(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setSystemWideTun(enabled)
    }

    fun setAllowLocalNetworks(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAllowLocalNetworks(enabled)
    }

    fun setKillSwitchEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKillSwitchEnabled(enabled)
    }

    /** One switch in the UI; an enum underneath, so further lists can be added
     *  without migrating what is already stored. */
    fun setDnsFilter(filter: DnsFilter) = viewModelScope.launch {
        settingsRepository.setDnsFilter(filter)
    }

    fun setEntryHopEnabled(enabled: Boolean) = viewModelScope.launch {
        changeRouting.setEntryHopEnabled(enabled).reportReconnect()
    }

    fun setWarpMode(mode: WarpMode) = viewModelScope.launch {
        changeRouting.setWarpMode(mode).reportReconnect()
    }

    fun setLearnBlockedSites(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setLearnBlockedSites(enabled)
    }

    fun setFailOpen(enabled: Boolean) = viewModelScope.launch {
        changeRouting.setFailOpen(enabled).reportReconnect()
    }

    fun setConnectionLog(enabled: Boolean) = viewModelScope.launch {
        changeRouting.setConnectionLog(enabled).reportReconnect()
    }

    private fun Result<Unit>.reportReconnect() {
        onFailure { _message.value = it.toError(Res.string.settings_switch_failed) }
    }

    fun setAutoConnectOnLaunch(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAutoConnectOnLaunch(enabled)
    }

    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAutoReconnect(enabled)
    }

    fun setCloseToTray(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setCloseToTray(enabled)
    }

    fun setAutoStartOnLogin(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAutoStartOnLogin(enabled).onFailure { failure ->
            _message.value = failure.toError(Res.string.settings_auto_start_failed)
        }
    }

    fun selectProfile(id: String) = viewModelScope.launch {
        switchProfile(id).fold(
            onSuccess = {
                profileRepository.profiles.first().firstOrNull { it.id == id }?.let { switched ->
                    _message.value = SettingsMessage.Info(
                        UiText.Resource(Res.string.settings_profile_switched, listOf(switched.name)),
                    )
                }
            },
            onFailure = { _message.value = it.toError(Res.string.settings_switch_failed) },
        )
    }

    fun renameProfile(id: String, name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = SettingsMessage.Error(UiText.Resource(Res.string.settings_profile_name_blank))
            return@launch
        }
        profileRepository.rename(id, trimmed)
        _message.value = SettingsMessage.Info(UiText.Resource(Res.string.settings_profile_renamed, listOf(trimmed)))
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        profileRepository.delete(id)
    }

    fun saveProfile(relay: ChainProfile, entryHop: WireGuardEntry?) = viewModelScope.launch {
        profileRepository.save(relay, entryHop)
        _message.value = SettingsMessage.Info(UiText.Resource(Res.string.settings_profile_saved))
    }

    fun clearProfile() = viewModelScope.launch {
        profileRepository.clear()
        _message.value = SettingsMessage.Info(UiText.Resource(Res.string.settings_profile_cleared))
    }

    /** Pasted or scanned text, reviewed before anything is saved. */
    fun importSecretsEnv(text: String) {
        if (relayPairing.accepts(text)) {
            pair(text)
        } else if (WireGuardConfParser.looksLikeConf(text)) {
            reviewConf(text, intoActive = false)
        } else {
            review(SecretsEnvParser.parse(text).map { PendingImport.SecretsEnv(text, it.relay, it.entryHop) })
        }
    }

    fun importAsEntryHop(text: String) = reviewConf(text, intoActive = true)

    /** A pairing link is the user's own act to scan or paste, and is single use, so it skips the review. */
    private fun pair(text: String) {
        if (pairingInFlight) return
        pairingInFlight = true
        _importsTaken.value++
        viewModelScope.launch {
            relayPairing.pair(text) { step, host -> _pairing.value = PairingStatus.Working(step, host) }.fold(
                onSuccess = { _pairing.value = PairingStatus.Done(it.host, it.admin) },
                onFailure = { _pairing.value = PairingStatus.Failed(it.toPairingText((_pairing.value as? PairingStatus.Working)?.host.orEmpty())) },
            )
            pairingInFlight = false
        }
    }

    fun dismissPairing() {
        _pairing.value = null
    }

    private fun Throwable.toPairingText(host: String): UiText {
        val failed = this as? PairingFailed
        val resource = when {
            failed == null -> return message?.let(UiText::Dynamic) ?: UiText.Resource(Res.string.settings_pairing_failed)
            failed.reason == PairingFailed.Reason.Expired -> Res.string.settings_pairing_expired
            failed.reason == PairingFailed.Reason.Used -> Res.string.settings_pairing_used
            failed.reason == PairingFailed.Reason.Unreachable && failed.step == PairingStep.Connecting -> Res.string.settings_pairing_unreachable
            failed.reason == PairingFailed.Reason.Unreachable -> Res.string.settings_pairing_no_answer
            failed.step == PairingStep.Reconnecting -> Res.string.settings_pairing_reconnect_failed
            else -> Res.string.settings_pairing_failed
        }
        return UiText.Resource(resource, listOf(host, failed.device))
    }

    private fun reviewConf(text: String, intoActive: Boolean) =
        review(WireGuardConfParser.parse(text).map { PendingImport.Conf(text, it, intoActive) })

    private fun review(parsed: Result<PendingImport>) {
        parsed.fold(
            onSuccess = {
                _importsTaken.value++
                _pendingImport.value = it
            },
            onFailure = { _message.value = it.toError(Res.string.settings_import_failed) },
        )
    }

    /** Saves the reviewed import as active (or as the entry hop); a reviewed conf also moves a wanted tunnel onto it. */
    fun usePendingImport() = viewModelScope.launch {
        when (val pending = _pendingImport.value ?: return@launch) {
            is PendingImport.Conf -> {
                _pendingImport.value = null
                importConf(pending.text, pending.intoActive)
            }
            is PendingImport.SecretsEnv -> {
                _pendingImport.value = null
                importSecretsEnvNow(pending.text)
            }
        }
    }

    /** Keeps the reviewed import as another connection without switching to it. */
    fun savePendingImport() = viewModelScope.launch {
        val pending = _pendingImport.value ?: return@launch
        _pendingImport.value = null
        val saved = when (pending) {
            is PendingImport.Conf -> importWireGuardConf.save(pending.text).map { it.endpointHost }
            is PendingImport.SecretsEnv -> importProfile.save(pending.text).map { it.vpsIp }
        }
        saved.fold(
            onSuccess = { _message.value = SettingsMessage.Info(UiText.Resource(Res.string.settings_import_saved, listOf(it))) },
            onFailure = { _message.value = it.toError(Res.string.settings_import_failed) },
        )
    }

    private suspend fun importSecretsEnvNow(text: String) {
        importProfile(text).fold(
            onSuccess = {
                _message.value = SettingsMessage.Info(
                    UiText.Resource(Res.string.settings_imported_profile, listOf(it.vpsIp)),
                )
            },
            onFailure = { _message.value = it.toError(Res.string.settings_import_failed) },
        )
    }

    fun cancelPendingImport() {
        _pendingImport.value = null
    }

    private suspend fun importConf(text: String, intoActive: Boolean) {
        importWireGuardConf(text, intoActive).fold(
            onSuccess = { imported ->
                val host = listOf(imported.entry.endpointHost)
                _message.value = if (imported.reconnect.isSuccess) {
                    SettingsMessage.Info(UiText.Resource(Res.string.settings_imported_profile, host))
                } else {
                    SettingsMessage.Error(UiText.Resource(Res.string.settings_imported_not_connected, host))
                }
            },
            onFailure = { _message.value = it.toError(Res.string.settings_import_failed) },
        )
    }

    /** Desktop convenience: import the CLI's secrets.env if it exists. The user's own file needs no review. */
    fun importDefaultSecretsEnv() {
        val text = readDefaultSecretsEnv()
        if (text == null) {
            _message.value = SettingsMessage.Error(UiText.Resource(Res.string.settings_no_secrets_env_found))
        } else {
            viewModelScope.launch { importSecretsEnvNow(text) }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun openSystemVpnSettings() = chainRepository.openSystemVpnSettings()

    private fun Throwable.toError(fallback: StringResource) =
        SettingsMessage.Error(message?.let(UiText::Dynamic) ?: UiText.Resource(fallback))
}
