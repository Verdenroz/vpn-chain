package com.verdenroz.vpnchain.app.di

import com.verdenroz.vpnchain.app.AppViewModel
import com.verdenroz.vpnchain.core.domain.di.domainModule
import com.verdenroz.vpnchain.core.update.di.updateModule
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
 * The headless half of the graph: profiles, settings, the tunnel and the use
 * cases over them. Start it with `startEngine` once Koin is up.
 */
val engineModules: List<Module> = listOf(domainModule)

/** Screens, ViewModels and the updater, which a headless engine process leaves out. */
val uiModules: List<Module> = listOf(
    updateModule,
    chainFeatureModule,
    settingsFeatureModule,
    logsFeatureModule,
    appModule,
)
