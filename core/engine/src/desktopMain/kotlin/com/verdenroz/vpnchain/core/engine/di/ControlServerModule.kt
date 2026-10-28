package com.verdenroz.vpnchain.core.engine.di

import com.verdenroz.vpnchain.core.common.appVersion
import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.di.startEngine
import com.verdenroz.vpnchain.core.engine.ControlServer
import com.verdenroz.vpnchain.core.engine.EngineHandler
import com.verdenroz.vpnchain.core.engine.EngineLock
import com.verdenroz.vpnchain.core.engine.EnginePaths
import com.verdenroz.vpnchain.core.engine.WindowRequests
import com.verdenroz.vpnchain.core.logging.Logger
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.koin.dsl.module

val controlServerModule = module {
    single { EnginePaths.system() }
    single { WindowRequests() }
    single {
        EngineHandler(
            chain = get(),
            profiles = get(),
            logs = get(),
            connect = get(),
            disconnect = get(),
            switchProfile = get(),
            settings = get<SettingsRepository>().settings,
            onShow = get<WindowRequests>()::request,
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
 * Starts the engine in this process and serves the control socket for it.
 * [lock] comes from `claimOrHandOff`; the server holds it until it stops.
 */
fun startHostedEngine(koin: Koin, lock: EngineLock) {
    startEngine(koin)
    try {
        if (koin.get<ControlServer>().start(lock) == null) lock.close()
    } catch (e: IOException) {
        lock.close()
        koin.get<Logger>().e(TAG, "cannot serve ${koin.get<EnginePaths>().socket}", e)
    }
}

private const val TAG = "Engine"
