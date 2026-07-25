package com.verdenroz.vpnchain.feature.logs.di

import com.verdenroz.vpnchain.feature.logs.LogsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val logsFeatureModule = module {
    viewModelOf(::LogsViewModel)
}
