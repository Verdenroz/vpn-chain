package com.verdenroz.vpnchain.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.control.ChainQuickControl
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.verdenroz.vpnchain.core.tunnel.R as TunnelR

/**
 * Quick Settings toggle for the chain. Passive tile: state is pushed while the
 * shade is open by mirroring the same status flow the Chain screen reads.
 * Consent-needing paths collapse the shade into MainActivity, which owns the
 * `VpnService.prepare` dialog.
 */
class VpnChainTileService : TileService(), KoinComponent {

    private val chainRepository: ChainRepository by inject()
    private val profileRepository: ProfileRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        listenJob?.cancel()
        listenJob = scope.launch {
            combine(chainRepository.status, profileRepository.profile) { status, profile ->
                status.state to (profile != null)
            }.collect { (state, hasProfile) -> render(state, hasProfile) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        scope.launch {
            when (val outcome = ChainQuickControl.toggle(this@VpnChainTileService)) {
                is ChainQuickControl.Outcome.Handled -> Unit
                is ChainQuickControl.Outcome.OpenApp -> collapseInto(outcome.intent)
            }
        }
    }

    private fun collapseInto(intent: Intent) {
        val launch = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(launch) else launch()
    }

    private fun render(state: TunnelState, hasProfile: Boolean) {
        val tile = qsTile ?: return
        tile.state = when {
            !hasProfile -> Tile.STATE_UNAVAILABLE
            state == TunnelState.Connected || state == TunnelState.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, TunnelR.drawable.ic_vpn_chain_logo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !hasProfile -> R.string.tile_subtitle_no_profile
                    state == TunnelState.Connected -> R.string.tile_subtitle_connected
                    state == TunnelState.Connecting -> R.string.tile_subtitle_connecting
                    state == TunnelState.Error -> R.string.tile_subtitle_error
                    else -> R.string.tile_subtitle_disconnected
                },
            )
        }
        tile.updateTile()
    }
}
