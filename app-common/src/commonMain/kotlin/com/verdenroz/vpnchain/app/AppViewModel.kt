package com.verdenroz.vpnchain.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.ThemeConfig
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Holds only what the app shell needs before the first frame: the theme choice and tunnel state. */
class AppViewModel(
    settingsRepository: SettingsRepository,
    chainRepository: ChainRepository,
) : ViewModel() {
    val themeConfig: StateFlow<ThemeConfig> =
        settingsRepository.settings.map { it.themeConfig }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeConfig.FOLLOW_SYSTEM,
        )

    val tunnelState: StateFlow<TunnelState> =
        chainRepository.status.map { it.state }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TunnelState.Disconnected,
        )
}
