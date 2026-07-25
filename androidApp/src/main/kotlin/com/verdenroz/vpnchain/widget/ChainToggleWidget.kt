package com.verdenroz.vpnchain.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.fillMaxSize
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.core.tunnel.R as TunnelR

/** 1x1 icon-button toggle */
class ChainToggleWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = rememberChainWidgetState(LocalContext.current)
            GlanceIconKey(
                icon = ImageProvider(TunnelR.drawable.ic_vpn_chain_logo),
                tint = state.stateColor,
                contentDescription = LocalContext.current.getString(R.string.widget_toggle_description),
                action = state.toggleAction(LocalContext.current),
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
    }
}

class ChainToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChainToggleWidget()
}
