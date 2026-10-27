package com.verdenroz.vpnchain.core.relay.di

import com.verdenroz.vpnchain.core.common.PrivateStorage
import com.verdenroz.vpnchain.core.relay.ManagementKey
import com.verdenroz.vpnchain.core.relay.fileManagementKey
import java.io.File
import org.koin.core.module.Module
import org.koin.dsl.module

actual val relayPlatformModule: Module = module {
    single<ManagementKey> { fileManagementKey(File(PrivateStorage.configDir, "management-key")) }
}
