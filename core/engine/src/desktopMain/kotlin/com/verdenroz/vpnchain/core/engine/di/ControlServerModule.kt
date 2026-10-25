package com.verdenroz.vpnchain.core.engine.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
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
        )
    }
    single {
        ControlServer(
            handler = get(),
            paths = get(),
            appVersion = System.getProperty("vpnchain.version").orEmpty(),
            scope = get<CoroutineScope>(applicationScopeQualifier),
            logger = get(),
        )
    }
}

/**
 * Starts the engine in this process and serves the control socket for it when
 * this process wins the engine lock. The lock is taken before the engine opens
 * DataStore; a process that loses it still runs its own engine but leaves the
 * socket to the winner.
 */
fun startHostedEngine(koin: Koin) {
    val paths = koin.get<EnginePaths>()
    val logger = koin.get<Logger>()
    val lock = EngineLock.tryAcquire(paths.lock)
    startEngine(koin)
    if (lock == null) {
        logger.w(TAG, "another vpn-chain engine holds ${paths.lock}; not serving ${paths.socket}")
        return
    }
    try {
        koin.get<ControlServer>().start(lock)
    } catch (e: IOException) {
        lock.close()
        logger.e(TAG, "cannot serve ${paths.socket}", e)
    }
}

private const val TAG = "Engine"
