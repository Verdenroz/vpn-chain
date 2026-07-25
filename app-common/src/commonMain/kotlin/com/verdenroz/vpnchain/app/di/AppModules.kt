package com.verdenroz.vpnchain.app.di

import com.verdenroz.vpnchain.app.AppViewModel
import com.verdenroz.vpnchain.core.domain.di.domainModule
import com.verdenroz.vpnchain.feature.chain.di.chainFeatureModule
import com.verdenroz.vpnchain.feature.logs.di.logsFeatureModule
import com.verdenroz.vpnchain.feature.settings.di.settingsFeatureModule
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

private val appModule = module {
    viewModelOf(::AppViewModel)
}

/**
 * The full Koin graph, minus the platform DataStore binding which each app
 * supplies (Android needs `androidContext`). Pass alongside platform modules to
 * `startKoin`.
 */
val appModules: List<Module> = listOf(
    domainModule,
    chainFeatureModule,
    settingsFeatureModule,
    logsFeatureModule,
    appModule,
)
