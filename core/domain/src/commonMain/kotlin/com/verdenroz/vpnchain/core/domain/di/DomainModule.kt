package com.verdenroz.vpnchain.core.domain.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.data.di.dataModule
import com.verdenroz.vpnchain.core.domain.ChainSupervisor
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.geoip.DefaultRouteGeolocator
import com.verdenroz.vpnchain.core.geoip.NetworkProbe
import com.verdenroz.vpnchain.core.geoip.RouteGeolocator
import com.verdenroz.vpnchain.core.geoip.createNetworkProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.dsl.module

val domainModule = module {
    includes(dataModule)
    single<RouteGeolocator> { DefaultRouteGeolocator() }
    single<NetworkProbe> { createNetworkProbe() }
    factory { ConnectChainUseCase(get()) }
    factory { DisconnectChainUseCase(get()) }
    factory { ImportProfileUseCase(get()) }
    // Singleton so origin-probe throttling holds across collectors.
    single { ObserveChainRouteUseCase(get(), get(), get(), get(), get()) }
    // Eager and app-scoped: it has to be watching before the first drop, and it
    // must outlive any ViewModel that happens to be on screen when one happens.
    single(createdAtStart = true) {
        ChainSupervisor(
            chain = get(),
            profiles = get(),
            settings = get(),
            network = get(),
        ).also { supervisor ->
            get<CoroutineScope>(applicationScopeQualifier).launch { supervisor.run() }
        }
    }
}
