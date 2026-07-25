package com.verdenroz.vpnchain.core.logging.di

import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.logging.createPlatformLogger
import org.koin.dsl.module

val loggingModule = module {
    single<Logger> { createPlatformLogger() }
}
