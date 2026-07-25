package com.verdenroz.vpnchain.core.datastore.di

import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides the platform DataStore<Preferences> (path differs per platform). */
expect val dataStorePlatformModule: Module

val dataStoreModule = module {
    includes(dataStorePlatformModule)
    single { VpnChainPreferencesDataSource(get()) }
}
