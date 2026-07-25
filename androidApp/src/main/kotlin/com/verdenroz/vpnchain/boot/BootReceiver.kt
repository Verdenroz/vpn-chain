package com.verdenroz.vpnchain.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Brings the chain up when the device finishes starting, if the user asked for
 * it.
 *
 * Deliberately silent when it can't proceed. VPN consent cannot be requested
 * without an activity, and throwing one on screen at boot — or worse, at every
 * boot — is not something a VPN should do uninvited. A user who never granted
 * consent simply gets no tunnel until they open the app.
 */
open class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val settingsRepository: SettingsRepository by inject()
    private val profileRepository: ProfileRepository by inject()
    private val connectChain: ConnectChainUseCase by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        // goAsync buys the ~10s this needs to read preferences and start the
        // service; onReceive itself must not block.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (!settingsRepository.settings.first().autoStartOnLogin) return@launch
                if (profileRepository.profile.first() == null) return@launch
                // Without prior consent the controller would only report a
                // permission error, so don't start a service to produce one.
                if (VpnService.prepare(context) != null) return@launch
                connectChain()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Direct-boot devices deliver this earlier; harmless to also accept.
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            // An app update stops the service without a matching restart.
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
