package com.verdenroz.vpnchain.core.tunnel.di

import com.verdenroz.vpnchain.core.config.AndroidLearnedRuleSetStore
import com.verdenroz.vpnchain.core.config.LearnedRuleSetStore
import com.verdenroz.vpnchain.core.tunnel.AndroidDestinationFeed
import com.verdenroz.vpnchain.core.tunnel.AndroidNetworkMonitor
import com.verdenroz.vpnchain.core.tunnel.AndroidTunnelController
import com.verdenroz.vpnchain.core.tunnel.DestinationFeed
import com.verdenroz.vpnchain.core.tunnel.NetworkMonitor
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import java.io.File
import org.koin.dsl.module

actual val tunnelModule: Module = module {
    single<TunnelController> { AndroidTunnelController(androidContext()) }
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single<DestinationFeed> { AndroidDestinationFeed() }
    single<LearnedRuleSetStore> {
        AndroidLearnedRuleSetStore(File(androidContext().filesDir, "sing-box").apply { mkdirs() })
    }
}
