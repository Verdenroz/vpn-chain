package com.verdenroz.vpnchain.core.tunnel.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.config.DesktopLearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.tunnel.ClashDestinationFeed
import com.verdenroz.vpnchain.core.tunnel.DesktopNetworkMonitor
import com.verdenroz.vpnchain.core.tunnel.DesktopTunnelController
import com.verdenroz.vpnchain.core.tunnel.DestinationFeed
import com.verdenroz.vpnchain.core.tunnel.NetworkMonitor
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

actual val tunnelModule: Module = module {
    single<TunnelController> {
        DesktopTunnelController(scope = get<CoroutineScope>(applicationScopeQualifier))
    }
    single<NetworkMonitor> { DesktopNetworkMonitor() }
    single<DestinationFeed> { ClashDestinationFeed() }
    single<LearnedRuleSetStore> { DesktopLearnedRuleSetStore() }
}
