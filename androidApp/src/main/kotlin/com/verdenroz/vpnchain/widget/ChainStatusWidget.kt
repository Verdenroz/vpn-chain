package com.verdenroz.vpnchain.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.DpSize
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.control.ChainQuickControl
import com.verdenroz.vpnchain.core.model.TunnelState

/**
 * Card widget: lamp + state readout with connect/disconnect, and (at 2-row
 * sizes) a Scan QR shortcut into the settings importer.
 */
class ChainStatusWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = rememberChainWidgetState(LocalContext.current)
            StatusCard(state)
        }
    }

    private companion object {
        val COMPACT = DpSize(180.dp, 56.dp)
        val TALL = DpSize(180.dp, 115.dp)
    }
}

@Composable
private fun StatusCard(state: ChainWidgetState) {
    val context = LocalContext.current
    val tall = LocalSize.current.height >= 100.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetPalette.shellFace)
            .cornerRadius(16.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .background(state.lampColor)
                    .cornerRadius(4.dp),
            ) {}
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = context.getString(R.string.widget_title),
                    style = TextStyle(
                        color = ColorProvider(WidgetPalette.muted),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = context.getString(state.stateLabelRes()),
                    style = TextStyle(
                        color = ColorProvider(WidgetPalette.readout),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            PanelKey(
                label = context.getString(
                    if (state.running) R.string.widget_disconnect else R.string.widget_connect,
                ),
                labelColor = if (state.running) WidgetPalette.lampRed else WidgetPalette.lampGreen,
                action = state.toggleAction(context),
            )
        }
        if (tall) {
            Spacer(GlanceModifier.height(12.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                PanelKey(
                    label = context.getString(R.string.widget_scan_qr),
                    labelColor = WidgetPalette.readout,
                    action = actionStartActivity(
                        ChainQuickControl.openAppIntent(context, MainActivity.ACTION_SCAN_QR),
                    ),
                )
            }
        }
    }
}

@Composable
private fun PanelKey(label: String, labelColor: Color, action: Action) {
    Box(
        modifier = GlanceModifier
            .background(WidgetPalette.shellRaised)
            .cornerRadius(6.dp)
            .clickable(action)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(labelColor),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

private fun ChainWidgetState.stateLabelRes(): Int = when {
    !hasProfile -> R.string.widget_state_no_profile
    state == TunnelState.Connected -> R.string.widget_state_connected
    state == TunnelState.Connecting -> R.string.widget_state_connecting
    state == TunnelState.Error -> R.string.widget_state_error
    else -> R.string.widget_state_disconnected
}

class ChainStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChainStatusWidget()
}
