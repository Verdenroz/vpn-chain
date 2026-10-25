package com.verdenroz.vpnchain.core.domain.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.data.di.startDataWatchers
import com.verdenroz.vpnchain.core.domain.ChainSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * Brings up the long-lived half of the graph. Call once, right after
 * `startKoin`, from whichever process owns the tunnel.
 *
 * [ChainSupervisor] has to be watching before the first drop, and must outlive
 * any ViewModel on screen when one happens.
 */
fun startEngine(koin: Koin) {
    startDataWatchers(koin)
    val supervisor = koin.get<ChainSupervisor>()
    koin.get<CoroutineScope>(applicationScopeQualifier).launch { supervisor.run() }
}
