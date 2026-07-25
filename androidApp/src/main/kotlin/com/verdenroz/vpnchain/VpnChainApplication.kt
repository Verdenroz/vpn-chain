package com.verdenroz.vpnchain

import android.app.Application
import com.verdenroz.vpnchain.app.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class VpnChainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@VpnChainApplication)
            modules(appModules)
        }
    }
}
