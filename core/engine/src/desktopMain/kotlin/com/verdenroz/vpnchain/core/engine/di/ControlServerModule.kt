package com.verdenroz.vpnchain.core.engine.di

import com.verdenroz.vpnchain.core.common.appVersion
import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.di.startEngine
import com.verdenroz.vpnchain.core.engine.ControlServer
import com.verdenroz.vpnchain.core.engine.EngineHandler
import com.verdenroz.vpnchain.core.engine.EngineLock
import com.verdenroz.vpnchain.core.engine.EnginePaths
import com.verdenroz.vpnchain.core.logging.Logger
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.koin.dsl.module

val controlServerModule = module {
    single { EnginePaths.system() }
    single {
        EngineHandler(
            chain = get(),
            profiles = get(),
            logs = get(),
            connect = get(),
            disconnect = get(),
            switchProfile = get(),
            settings = get<SettingsRepository>().settings,
        )
    }
    single {
        ControlServer(
            handler = get(),
            paths = get(),
            appVersion = appVersion,
            scope = get<CoroutineScope>(applicationScopeQualifier),
            logger = get(),
        )
    }
}

/**
 * Starts the engine in this process and serves the control socket for it when
 * this process wins the engine lock. The lock only decides who serves the
 * socket: a second GUI that loses it still runs its own engine, DataStore
 * included, until the GUI becomes a client of a running engine.
 */
fun startHostedEngine(koin: Koin) {
    val paths = koin.get<EnginePaths>()
    val logger = koin.get<Logger>()
    val lock = try {
        EngineLock.tryAcquire(paths.lock)
    } catch (e: IOException) {
        logger.e(TAG, "cannot open ${paths.lock}", e)
        null
    }
    startEngine(koin)
    if (lock == null) {
        logger.w(TAG, "another vpn-chain engine holds ${paths.lock}; not serving ${paths.socket}")
        return
    }
    try {
        if (koin.get<ControlServer>().start(lock) == null) lock.close()
    } catch (e: IOException) {
        lock.close()
        logger.e(TAG, "cannot serve ${paths.socket}", e)
    }
}

private const val TAG = "Engine"
