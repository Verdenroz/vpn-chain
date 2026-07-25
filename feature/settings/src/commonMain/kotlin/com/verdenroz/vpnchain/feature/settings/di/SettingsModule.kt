package com.verdenroz.vpnchain.feature.settings.di

import com.verdenroz.vpnchain.feature.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsFeatureModule = module {
    viewModelOf(::SettingsViewModel)
}
