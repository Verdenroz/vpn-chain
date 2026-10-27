package com.verdenroz.vpnchain.core.relay.di

import org.koin.core.module.Module
import org.koin.dsl.module

/** Binds the platform [com.verdenroz.vpnchain.core.relay.ManagementKey]. */
expect val relayPlatformModule: Module

val relayModule = module {
    includes(relayPlatformModule)
}
