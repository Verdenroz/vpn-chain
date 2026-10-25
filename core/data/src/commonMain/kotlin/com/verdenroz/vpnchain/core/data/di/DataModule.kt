package com.verdenroz.vpnchain.core.data.di

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.common.di.commonModule
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ConnectivityRepository
import com.verdenroz.vpnchain.core.data.DefaultChainRepository
import com.verdenroz.vpnchain.core.data.DefaultConnectivityRepository
import com.verdenroz.vpnchain.core.data.DefaultLearnedSitesRepository
import com.verdenroz.vpnchain.core.data.DefaultLogRepository
import com.verdenroz.vpnchain.core.data.DefaultOriginRepository
import com.verdenroz.vpnchain.core.data.DefaultProfileRepository
import com.verdenroz.vpnchain.core.data.DefaultSettingsRepository
import com.verdenroz.vpnchain.core.data.DefaultWarpRepository
import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.LogRepository
import com.verdenroz.vpnchain.core.data.OriginRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.data.SiteLearner
import com.verdenroz.vpnchain.core.data.WarpRepository
import com.verdenroz.vpnchain.core.data.learningSessionOf
import com.verdenroz.vpnchain.core.datastore.di.dataStoreModule
import com.verdenroz.vpnchain.core.logging.di.loggingModule
import com.verdenroz.vpnchain.core.tunnel.ProbeGate
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import com.verdenroz.vpnchain.core.tunnel.di.tunnelModule
import com.verdenroz.vpnchain.core.warp.di.warpModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import org.koin.core.Koin
import org.koin.dsl.module

val dataModule = module {
    includes(commonModule, loggingModule, dataStoreModule, tunnelModule, warpModule)

    single<ProfileRepository> { DefaultProfileRepository(get()) }
    single<OriginRepository> { DefaultOriginRepository(get()) }
    single<SettingsRepository> { DefaultSettingsRepository(get(), get()) }
    single<ConnectivityRepository> { DefaultConnectivityRepository(get()) }
    single<WarpRepository> {
        DefaultWarpRepository(
            preferences = get(),
            registrar = get(),
            logger = get(),
            now = ::currentTimeMillis,
        )
    }
    single<LearnedSitesRepository> {
        DefaultLearnedSitesRepository(
            preferences = get(),
            store = get(),
            now = ::currentTimeMillis,
            logger = get(),
        )
    }
    single {
        SiteLearner(
            feed = get(),
            prober = get(),
            learned = get(),
            settings = get<SettingsRepository>().settings,
            profile = get<ProfileRepository>().profile,
            gate = get<ProbeGate>().open,
            sessions = get<TunnelController>().runningConfig.map(::learningSessionOf),
            status = get<TunnelController>().status,
            now = ::currentTimeMillis,
            scope = get<CoroutineScope>(applicationScopeQualifier),
        )
    }
    // Named rather than positional: every collaborator here is resolved by a
    // bare get(), so a reordered parameter would bind silently and wrongly.
    single<ChainRepository> {
        DefaultChainRepository(
            controller = get(),
            profileRepository = get(),
            settingsRepository = get(),
            warpRepository = get(),
            learnedSites = get(),
            learnedStore = get(),
            preferences = get(),
            logger = get(),
            scope = get<CoroutineScope>(applicationScopeQualifier),
        )
    }
    single<LogRepository> {
        DefaultLogRepository(
            controller = get(),
            scope = get<CoroutineScope>(applicationScopeQualifier),
            now = ::currentTimeMillis,
        )
    }
}

/**
 * Resolves the data layer's watchers, which act without a screen open. Called
 * by the engine's start, not on its own.
 *
 * - [SiteLearner] has to be listening before the first connection.
 * - [ChainRepository] has to be watching for out-of-app stops (Android's
 *   notification key, an OS revoke) before one happens.
 * - [LogRepository] buffers tunnel output from the first line.
 */
fun startDataWatchers(koin: Koin) {
    koin.get<SiteLearner>()
    koin.get<ChainRepository>()
    koin.get<LogRepository>()
}
