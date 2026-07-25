package com.verdenroz.vpnchain.core.tunnel.di

import com.verdenroz.vpnchain.core.tunnel.AndroidTunnelController
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val tunnelModule: Module = module {
    single<TunnelController> { AndroidTunnelController(androidContext()) }
}
