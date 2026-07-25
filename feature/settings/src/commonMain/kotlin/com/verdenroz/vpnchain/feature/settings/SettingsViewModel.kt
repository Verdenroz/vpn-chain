package com.verdenroz.vpnchain.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_auto_start_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_import_failed
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_imported_profile
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_no_secrets_env_found
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_cleared
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_saved
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_profile_switched
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val profile: ChainProfile? = null,
    val killSwitchGuidanceSupported: Boolean = false,
    val alwaysOnDetected: Boolean = false,
    val autostartSupported: Boolean = false,
    val profiles: List<SavedProfile> = emptyList(),
    val activeProfileId: String? = null,
)

/** One-shot user feedback (import result), consumed by the UI. */
sealed interface SettingsMessage {
    data class Info(val text: UiText) : SettingsMessage
    data class Error(val text: UiText) : SettingsMessage
}

class SettingsViewModel(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val importProfile: ImportProfileUseCase,
    private val chainRepository: ChainRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            profileRepository.profile,
            chainRepository.alwaysOnDetected,
            profileRepository.profiles,
            profileRepository.activeProfileId,
        ) { settings, profile, alwaysOnDetected, profiles, activeProfileId ->
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

    fun setThemeConfig(themeConfig: ThemeConfig) = viewModelScope.launch {
        settingsRepository.setThemeConfig(themeConfig)
    }

    fun setSystemWideTun(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setSystemWideTun(enabled)
    }

    fun setKillSwitchEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKillSwitchEnabled(enabled)
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
            _message.value = SettingsMessage.Error(
                failure.message?.let(UiText::Dynamic)
                    ?: UiText.Resource(Res.string.settings_auto_start_failed),
            )
        }
    }

    /**
     * A live tunnel is still running the old chain, so switching means taking
     * it down and bringing it back up rather than reconfiguring in place.
     */
    fun selectProfile(id: String) = viewModelScope.launch {
        if (id == profileRepository.activeProfileId.first()) return@launch
        val wasConnected = chainRepository.status.first().state == TunnelState.Connected
        profileRepository.setActive(id)
        if (wasConnected) {
            chainRepository.disconnect()
            chainRepository.connect()
        }
        profileRepository.profiles.first().firstOrNull { it.id == id }?.let { switched ->
            _message.value = SettingsMessage.Info(
                UiText.Resource(Res.string.settings_profile_switched, listOf(switched.name)),
            )
        }
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        profileRepository.delete(id)
    }

    fun saveProfile(profile: ChainProfile) = viewModelScope.launch {
        profileRepository.save(profile)
        _message.value = SettingsMessage.Info(UiText.Resource(Res.string.settings_profile_saved))
    }

    fun clearProfile() = viewModelScope.launch {
        profileRepository.clear()
        _message.value = SettingsMessage.Info(UiText.Resource(Res.string.settings_profile_cleared))
    }

    fun importSecretsEnv(text: String) = viewModelScope.launch {
        importProfile(text).fold(
            onSuccess = {
                _message.value = SettingsMessage.Info(
                    UiText.Resource(Res.string.settings_imported_profile, listOf(it.vpsIp)),
                )
            },
            onFailure = {
                _message.value = SettingsMessage.Error(
                    it.message?.let(UiText::Dynamic) ?: UiText.Resource(Res.string.settings_import_failed),
                )
            },
        )
    }

    /** Desktop convenience: import the CLI's secrets.env if it exists. */
    fun importDefaultSecretsEnv() {
        val text = readDefaultSecretsEnv()
        if (text == null) {
            _message.value = SettingsMessage.Error(UiText.Resource(Res.string.settings_no_secrets_env_found))
        } else {
            importSecretsEnv(text)
        }
    }

    fun consumeMessage() { _message.value = null }

    fun openSystemVpnSettings() = chainRepository.openSystemVpnSettings()
}
