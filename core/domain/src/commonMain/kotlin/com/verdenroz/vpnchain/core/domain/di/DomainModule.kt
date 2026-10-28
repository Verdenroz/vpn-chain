package com.verdenroz.vpnchain.core.domain.di

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.data.di.dataModule
import com.verdenroz.vpnchain.core.domain.ChainSupervisor
import com.verdenroz.vpnchain.core.domain.ChangeRoutingUseCase
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ImportProfileUseCase
import com.verdenroz.vpnchain.core.domain.ImportWireGuardConfUseCase
import com.verdenroz.vpnchain.core.domain.ManageDevicesUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.domain.PairRelayUseCase
import com.verdenroz.vpnchain.core.domain.RelayDevices
import com.verdenroz.vpnchain.core.domain.RelayPairing
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
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
    factory { ImportWireGuardConfUseCase(get(), get()) }
    factory<RelayPairing> { PairRelayUseCase(get(), get(), get(), get(), get()) { currentTimeMillis() / 1000 } }
    factory<RelayDevices> { ManageDevicesUseCase(get(), get(), get()) }
    factory { SwitchProfileUseCase(get(), get()) }
    factory { ChangeRoutingUseCase(get(), get()) }
    // Singleton so origin-probe throttling holds across collectors.
    single { ObserveChainRouteUseCase(get(), get(), get(), get(), get(), get()) }
    single {
        ChainSupervisor(
            chain = get(),
            profiles = get(),
            settings = get(),
            network = get(),
        )
    }
}
