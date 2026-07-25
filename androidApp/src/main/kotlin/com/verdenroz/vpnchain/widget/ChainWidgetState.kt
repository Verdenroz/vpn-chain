package com.verdenroz.vpnchain.widget

import android.content.Context
import android.net.VpnService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.unit.ColorProvider
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.control.ChainQuickControl
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.TunnelState
import org.koin.core.context.GlobalContext

internal data class ChainWidgetState(
    val state: TunnelState,
    val hasProfile: Boolean,
    val needsConsent: Boolean,
) {
    val running: Boolean get() = state == TunnelState.Connected || state == TunnelState.Connecting

    /** The logo mark doubles as the lamp: green up, amber in transit, red down. */
    val stateColor: ColorProvider
        get() = when (state) {
            TunnelState.Connected -> WidgetPalette.green
            TunnelState.Connecting -> WidgetPalette.amber
            else -> WidgetPalette.red
        }

    /**
     * Consent and profile setup need the app, and widget activity launches
     * must ride a PendingIntent (`actionStartActivity`) — so those cases
     * pre-route to MainActivity instead of the background callback.
     */
    fun toggleAction(context: Context): Action = when {
        !hasProfile -> actionStartActivity(ChainQuickControl.openAppIntent(context, null))
        !running && needsConsent ->
            actionStartActivity(ChainQuickControl.openAppIntent(context, MainActivity.ACTION_CONNECT))
        else -> actionRunCallback<ToggleChainAction>()
    }
}

@Composable
internal fun rememberChainWidgetState(context: Context): ChainWidgetState {
    val koin = GlobalContext.get()
    val status by koin.get<ChainRepository>().status.collectAsState(ChainStatus())
    val profile by koin.get<ProfileRepository>().profile.collectAsState(null)
    return ChainWidgetState(
        state = status.state,
        hasProfile = profile != null,
        needsConsent = VpnService.prepare(context) != null,
    )
}
