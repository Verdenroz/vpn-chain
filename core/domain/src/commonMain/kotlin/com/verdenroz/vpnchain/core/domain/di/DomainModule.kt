package com.verdenroz.vpnchain.core.domain.di

import com.verdenroz.vpnchain.core.data.di.dataModule
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.geoip.DefaultRouteGeolocator
import com.verdenroz.vpnchain.core.geoip.NetworkProbe
import com.verdenroz.vpnchain.core.geoip.RouteGeolocator
import com.verdenroz.vpnchain.core.geoip.createNetworkProbe
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
}
