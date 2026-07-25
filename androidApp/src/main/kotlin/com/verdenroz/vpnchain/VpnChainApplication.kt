package com.verdenroz.vpnchain

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.verdenroz.vpnchain.app.di.appModules
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.notification.ChainNotificationUpdater
import com.verdenroz.vpnchain.widget.ChainControlsWidget
import com.verdenroz.vpnchain.widget.ChainToggleWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class VpnChainApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@VpnChainApplication)
            modules(appModules)
        }
        pushChainStateToWidgets()
        ChainNotificationUpdater(this, get(), get()).start(appScope)
    }

    /**
     * Glance widgets only re-render on an explicit update, so mirror tunnel
     * state transitions out to them. The service runs in this process, meaning
     * every transition happens with this collector alive.
     */
    private fun pushChainStateToWidgets() {
        val chainRepository = get<ChainRepository>()
        val profileRepository = get<ProfileRepository>()
        appScope.launch {
            combine(chainRepository.status, profileRepository.profile) { status, profile ->
                status.state to (profile != null)
            }
                .distinctUntilChanged()
                .collect {
                    ChainToggleWidget().updateAll(this@VpnChainApplication)
                    ChainControlsWidget().updateAll(this@VpnChainApplication)
                }
        }
    }
}
