package com.verdenroz.vpnchain.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.core.tunnel.R as TunnelR

/** 1x1 icon-only toggle: the whole face is the button, the tint is the lamp. */
class ChainToggleWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = rememberChainWidgetState(LocalContext.current)
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetPalette.shellFace)
                    .cornerRadius(20.dp)
                    .clickable(state.toggleAction(LocalContext.current)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(TunnelR.drawable.ic_vpn_chain_link),
                    contentDescription = LocalContext.current.getString(R.string.widget_toggle_description),
                    colorFilter = ColorFilter.tint(ColorProvider(state.lampColor)),
                    modifier = GlanceModifier.size(30.dp).padding(2.dp),
                )
            }
        }
    }
}

class ChainToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChainToggleWidget()
}
