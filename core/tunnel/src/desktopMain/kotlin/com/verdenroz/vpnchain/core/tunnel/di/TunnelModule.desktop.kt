package com.verdenroz.vpnchain.core.tunnel.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.config.DesktopLearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.tunnel.ClashConnectionCloser
import com.verdenroz.vpnchain.core.tunnel.ClashDestinationFeed
import com.verdenroz.vpnchain.core.tunnel.ConnectionCloser
import com.verdenroz.vpnchain.core.tunnel.DesktopNetworkMonitor
import com.verdenroz.vpnchain.core.tunnel.DesktopTunnelController
import com.verdenroz.vpnchain.core.tunnel.DestinationFeed
import com.verdenroz.vpnchain.core.tunnel.NetworkMonitor
import com.verdenroz.vpnchain.core.tunnel.OpenProbeGate
import com.verdenroz.vpnchain.core.tunnel.ProbeGate
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import com.verdenroz.vpnchain.core.tunnel.bundledTool
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

actual val tunnelModule: Module = module {
    single<TunnelController> {
        DesktopTunnelController(
            scope = get<CoroutineScope>(applicationScopeQualifier),
            singBoxBin = bundledTool("sing-box"),
            killSwitchBin = bundledTool("vpn-chain-killswitch"),
        )
    }
    single<NetworkMonitor> { DesktopNetworkMonitor() }
    single<ProbeGate> { OpenProbeGate() }
    single<DestinationFeed> { ClashDestinationFeed() }
    single<ConnectionCloser> { ClashConnectionCloser(get<TunnelController>().runningConfig) }
    single<LearnedRuleSetStore> { DesktopLearnedRuleSetStore() }
}
