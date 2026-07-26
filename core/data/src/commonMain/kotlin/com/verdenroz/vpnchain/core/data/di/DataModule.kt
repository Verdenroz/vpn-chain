package com.verdenroz.vpnchain.core.data.di

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.common.di.commonModule
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ConnectivityRepository
import com.verdenroz.vpnchain.core.data.DefaultChainRepository
import com.verdenroz.vpnchain.core.data.DefaultConnectivityRepository
import com.verdenroz.vpnchain.core.data.DefaultLogRepository
import com.verdenroz.vpnchain.core.data.DefaultOriginRepository
import com.verdenroz.vpnchain.core.data.DefaultProfileRepository
import com.verdenroz.vpnchain.core.data.DefaultSettingsRepository
import com.verdenroz.vpnchain.core.data.LogRepository
import com.verdenroz.vpnchain.core.data.OriginRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.datastore.di.dataStoreModule
import com.verdenroz.vpnchain.core.logging.di.loggingModule
import com.verdenroz.vpnchain.core.tunnel.di.tunnelModule
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

val dataModule = module {
    includes(commonModule, loggingModule, dataStoreModule, tunnelModule)

    single<ProfileRepository> { DefaultProfileRepository(get()) }
    single<OriginRepository> { DefaultOriginRepository(get()) }
    single<SettingsRepository> { DefaultSettingsRepository(get(), get()) }
    single<ConnectivityRepository> { DefaultConnectivityRepository(get()) }
    // Named rather than positional: every collaborator here is resolved by a
    // bare get(), so a reordered parameter would bind silently and wrongly.
    // Eager: it has to be watching for out-of-app stops (Android's notification
    // key, an OS revoke) before one happens, not from first UI resolution.
    single<ChainRepository>(createdAtStart = true) {
        DefaultChainRepository(
            controller = get(),
            profileRepository = get(),
            settingsRepository = get(),
            preferences = get(),
            logger = get(),
            scope = get<CoroutineScope>(applicationScopeQualifier),
        )
    }
    single<LogRepository>(createdAtStart = true) {
        DefaultLogRepository(
            controller = get(),
            scope = get<CoroutineScope>(applicationScopeQualifier),
            now = ::currentTimeMillis,
        )
    }
}
