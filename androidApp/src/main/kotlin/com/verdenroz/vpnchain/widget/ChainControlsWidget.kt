package com.verdenroz.vpnchain.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.control.ChainQuickControl
import com.verdenroz.vpnchain.core.tunnel.R as TunnelR

/** 2x1 control strip: one continuous face split into toggle and QR halves. */
class ChainControlsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = rememberChainWidgetState(LocalContext.current)
            val local = LocalContext.current
            // Fixed-height capsule matching the 1x1 circle's geometry.
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(KEY_SIZE)
                        .background(WidgetPalette.outline)
                        .cornerRadius(KEY_SIZE / 2)
                        .padding(2.dp),
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(WidgetPalette.shellFace)
                            .cornerRadius((KEY_SIZE / 2) - 2.dp),
                    ) {
                        Row(modifier = GlanceModifier.fillMaxSize()) {
                        StripHalf(
                            icon = ImageProvider(TunnelR.drawable.ic_vpn_chain_logo),
                            tint = state.stateColor,
                            contentDescription = local.getString(R.string.widget_toggle_description),
                            action = state.toggleAction(local),
                        )
                        Box(
                            GlanceModifier.width(2.dp).fillMaxHeight()
                                .background(WidgetPalette.outline),
                        ) {}
                        StripHalf(
                            icon = ImageProvider(R.drawable.ic_scan_qr),
                            tint = WidgetPalette.engraved,
                            contentDescription = local.getString(R.string.widget_scan_qr),
                            action = actionStartActivity(
                                ChainQuickControl.openAppIntent(local, MainActivity.ACTION_SCAN_QR),
                            ),
                        )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StripHalf(
    icon: ImageProvider,
    tint: ColorProvider,
    contentDescription: String,
    action: Action,
) {
    Box(
        modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = icon,
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(28.dp),
        )
    }
}

class ChainControlsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChainControlsWidget()
}
