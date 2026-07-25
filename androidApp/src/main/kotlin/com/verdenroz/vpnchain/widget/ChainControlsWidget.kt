package com.verdenroz.vpnchain.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import com.verdenroz.vpnchain.MainActivity
import com.verdenroz.vpnchain.R
import com.verdenroz.vpnchain.control.ChainQuickControl
import com.verdenroz.vpnchain.core.common.formatBytes
import com.verdenroz.vpnchain.core.common.formatRate
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.HopRole
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.tunnel.R as TunnelR
import org.koin.core.context.GlobalContext

/**
 * Control strip: one capsule split into toggle and QR halves. Resized taller
 * it grows a metrics readout (exit IP, RTT/location, live rates).
 */
class ChainControlsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, TALL, XTALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = rememberChainWidgetState(LocalContext.current)
            val height = LocalSize.current.height
            when {
                height >= XTALL.height -> Column(modifier = GlanceModifier.fillMaxSize()) {
                    ControlCapsule(state, modifier = GlanceModifier.fillMaxWidth().height(KEY_SIZE))
                    Spacer(GlanceModifier.height(6.dp))
                    // Room for the full readout, so let it fill the cell.
                    MetricsPanel(
                        expanded = true,
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    )
                }
                height >= TALL.height -> Column(modifier = GlanceModifier.fillMaxSize()) {
                    ControlCapsule(state, modifier = GlanceModifier.fillMaxWidth().height(KEY_SIZE))
                    Spacer(GlanceModifier.height(6.dp))
                    // Cap the readout height: a short readout stretched to fill
                    // reads as a slab of empty panel, not an instrument.
                    MetricsPanel(
                        expanded = false,
                        modifier = GlanceModifier.fillMaxWidth()
                            .height(minOf(height - KEY_SIZE - 6.dp, 84.dp)),
                    )
                }
                else -> Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ControlCapsule(state, modifier = GlanceModifier.fillMaxWidth().height(KEY_SIZE))
                }
            }
        }
    }

    private companion object {
        val COMPACT = DpSize(110.dp, 50.dp)
        val TALL = DpSize(110.dp, 115.dp)
        val XTALL = DpSize(110.dp, 180.dp)
    }
}

@Composable
private fun ControlCapsule(state: ChainWidgetState, modifier: GlanceModifier) {
    val local = LocalContext.current
    Box(
        modifier = modifier
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

/** Live readout fed by the same route/stats flows the Chain screen reads. */
@Composable
private fun MetricsPanel(expanded: Boolean, modifier: GlanceModifier) {
    val local = LocalContext.current
    val koin = remember { GlobalContext.get() }
    val stats by koin.get<ChainRepository>().stats.collectAsState(SessionStats())
    val route by remember { koin.get<ObserveChainRouteUseCase>()() }.collectAsState(ChainRoute())

    val exit = route.hop(HopRole.Exit)
    val rttLine = listOfNotNull(
        route.throughRttMs?.let { local.getString(R.string.notification_rtt, it) },
        exit?.location?.countryName,
    ).joinToString(" · ").ifEmpty { PLACEHOLDER }
    val rateLine = if (stats.hasTraffic) {
        "↓ ${formatRate(stats.downlinkBytesPerSecond)} · ↑ ${formatRate(stats.uplinkBytesPerSecond)}"
    } else {
        PLACEHOLDER
    }
    val sessionLine = buildList {
        if (stats.hasTraffic) add(formatBytes(stats.uplinkBytes + stats.downlinkBytes))
        stats.connectedSinceMillis?.let { add(coarseUptime(local, System.currentTimeMillis() - it)) }
    }.joinToString(" · ").ifEmpty { PLACEHOLDER }

    Box(
        modifier = modifier
            .background(WidgetPalette.outline)
            .cornerRadius(16.dp)
            .padding(2.dp)
            .clickable(actionStartActivity(ChainQuickControl.openAppIntent(local, null))),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetPalette.shellFace)
                .cornerRadius(14.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (expanded) {
                // Weight spacers spread the rows across whatever height the
                // user dragged out, so a big cell fills with readout, not face.
                // The label column already says RTT, so the value drops its prefix.
                val rttValue = listOfNotNull(
                    route.throughRttMs?.let { "$it ms" },
                    exit?.location?.countryName,
                ).joinToString(" · ").ifEmpty { PLACEHOLDER }
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    MetricRow(local.getString(R.string.widget_label_exit), exit?.ip ?: PLACEHOLDER, strong = true)
                    Spacer(GlanceModifier.defaultWeight())
                    MetricRow(local.getString(R.string.widget_label_rtt), rttValue)
                    Spacer(GlanceModifier.defaultWeight())
                    MetricRow(local.getString(R.string.widget_label_rate), rateLine)
                    Spacer(GlanceModifier.defaultWeight())
                    MetricRow(local.getString(R.string.widget_label_session), sessionLine)
                }
            } else {
                Column {
                    Text(
                        text = exit?.ip ?: PLACEHOLDER,
                        maxLines = 1,
                        style = TextStyle(
                            color = WidgetPalette.readout,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        text = rttLine,
                        maxLines = 1,
                        style = TextStyle(color = WidgetPalette.engraved, fontSize = 10.sp),
                    )
                    Text(
                        text = rateLine,
                        maxLines = 1,
                        style = TextStyle(color = WidgetPalette.engraved, fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, strong: Boolean = false) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = WidgetPalette.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.width(48.dp),
        )
        Text(
            text = value,
            maxLines = 1,
            style = TextStyle(
                color = if (strong) WidgetPalette.readout else WidgetPalette.engraved,
                fontSize = if (strong) 13.sp else 11.sp,
                fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

/**
 * Minute-granular on purpose: the widget refreshes on a 30s tick, so a
 * seconds display would sit frozen between ticks and read as a hang.
 */
private fun coarseUptime(context: Context, millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> context.getString(R.string.widget_uptime_now)
        minutes < 60 -> context.getString(R.string.widget_uptime_minutes, minutes)
        else -> context.getString(R.string.widget_uptime_hours, minutes / 60, minutes % 60)
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

private const val PLACEHOLDER = "—"

class ChainControlsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChainControlsWidget()
}
