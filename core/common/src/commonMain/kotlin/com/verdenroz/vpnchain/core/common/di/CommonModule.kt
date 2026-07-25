package com.verdenroz.vpnchain.core.common.di

import com.verdenroz.vpnchain.core.common.autostart.LoginAutostart
import com.verdenroz.vpnchain.core.common.autostart.createLoginAutostart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the app-lifetime [CoroutineScope] */
val applicationScopeQualifier = named("vpnchain.applicationScope")

/** Qualifier for the IO dispatcher, so consumers stay testable. */
val ioDispatcherQualifier = named("vpnchain.ioDispatcher")

val commonModule = module {
    single(applicationScopeQualifier) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single(ioDispatcherQualifier) { Dispatchers.IO }
    single<LoginAutostart> { createLoginAutostart() }
}
