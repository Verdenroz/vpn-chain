package com.verdenroz.vpnchain.core.relay.di

import com.verdenroz.vpnchain.core.relay.ManagementKey
import com.verdenroz.vpnchain.core.relay.keystoreManagementKey
import org.koin.core.module.Module
import org.koin.dsl.module

actual val relayPlatformModule: Module = module {
    single<ManagementKey> { keystoreManagementKey() }
}
