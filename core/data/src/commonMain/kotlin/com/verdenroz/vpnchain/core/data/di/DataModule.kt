package com.verdenroz.vpnchain.core.data.di

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.common.di.commonModule
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.DefaultChainRepository
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
    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<ChainRepository> { DefaultChainRepository(get(), get(), get(), get()) }
    single<LogRepository>(createdAtStart = true) {
        DefaultLogRepository(
            controller = get(),
            scope = get<CoroutineScope>(applicationScopeQualifier),
            now = ::currentTimeMillis,
        )
    }
}
