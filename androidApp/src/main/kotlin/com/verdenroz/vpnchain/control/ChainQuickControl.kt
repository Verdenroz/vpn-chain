package com.verdenroz.vpnchain.control

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * One connect/disconnect path for every surface outside the activity (QS tile,
 * home-screen widgets). Cases that can only be completed with UI — VPN consent,
 * or a missing profile — are returned as an [Outcome.OpenApp] intent because
 * each surface launches activities differently (tile must collapse the shade).
 */
object ChainQuickControl : KoinComponent {

    private val chainRepository: ChainRepository by inject()
    private val profileRepository: ProfileRepository by inject()
    private val connectChain: ConnectChainUseCase by inject()
    private val disconnectChain: DisconnectChainUseCase by inject()

    sealed interface Outcome {
        /** The toggle ran (or started) in the background; no UI needed. */
        data object Handled : Outcome

        /** The caller must launch this to finish the request in the app. */
        data class OpenApp(val intent: Intent) : Outcome
    }

    suspend fun toggle(context: Context): Outcome {
        val state = chainRepository.status.first().state
        return when {
            state == TunnelState.Connected || state == TunnelState.Connecting -> {
                disconnectChain()
                Outcome.Handled
            }
            profileRepository.profile.first() == null -> Outcome.OpenApp(openAppIntent(context, null))
            VpnService.prepare(context) != null ->
                Outcome.OpenApp(openAppIntent(context, MainActivity.ACTION_CONNECT))
            else -> {
                connectChain()
                Outcome.Handled
            }
        }
    }

    fun openAppIntent(context: Context, action: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            action?.let { setAction(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
