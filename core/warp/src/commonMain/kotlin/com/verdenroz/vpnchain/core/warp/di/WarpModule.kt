package com.verdenroz.vpnchain.core.warp.di

import com.verdenroz.vpnchain.core.warp.DefaultWarpRegistrar
import com.verdenroz.vpnchain.core.warp.WarpPlatformClient
import com.verdenroz.vpnchain.core.warp.WarpRegistrar
import com.verdenroz.vpnchain.core.warp.createWarpPlatformClient
import org.koin.dsl.module

val warpModule = module {
    single<WarpPlatformClient> { createWarpPlatformClient() }
    single<WarpRegistrar> { DefaultWarpRegistrar(get()) }
}
