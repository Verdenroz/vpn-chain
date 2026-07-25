package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.generated.resources.Res
import com.verdenroz.vpnchain.core.designsystem.generated.resources.status_connected
import com.verdenroz.vpnchain.core.designsystem.generated.resources.status_connecting
import com.verdenroz.vpnchain.core.designsystem.generated.resources.status_disconnected
import com.verdenroz.vpnchain.core.designsystem.generated.resources.status_error
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.TunnelState
import org.jetbrains.compose.resources.stringResource

/**
 * A panel lamp: a lens sunk in a dark well, a hot core where the die sits, and
 * — on a dark enclosure — light spilling onto the metal around it. Unlit, the
 * lens is still there, just dead: a lamp that vanishes when off is a dot, not a
 * component.
 */
@Composable
fun IndicatorLamp(
    color: Color,
    lit: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    pulsing: Boolean = false,
) {
    val colors = PanelTheme.colors
    val pulse = rememberInfiniteTransition(label = "lamp-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.42f else 1f,
        // Slow asymmetric breathing — a filament warming, not a blink.
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "lamp-alpha",
    )
    val intensity by animateFloatAsState(
        targetValue = if (lit) pulseAlpha else 0f,
        animationSpec = tween(260),
        label = "lamp-intensity",
    )

    // Bloom needs room outside the lens; the well is drawn at the nominal size.
    Canvas(modifier.size(size * BLOOM_EXTENT)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val lensRadius = size.toPx() / 2f
        val dead = lerp(colors.shellDeep, color, if (colors.dark) 0.16f else 0.22f)

        if (intensity > 0.01f && colors.dark) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.30f * intensity), Color.Transparent),
                    center = center,
                    radius = lensRadius * BLOOM_EXTENT,
                ),
                radius = lensRadius * BLOOM_EXTENT,
                center = center,
            )
        }

        // Well: the lens sits below the panel face, so its top edge is shadow.
        drawCircle(colors.shellDeep, radius = lensRadius + 1.5f, center = center)
        drawCircle(
            color = colors.edgeHighlight.copy(alpha = 0.35f),
            radius = lensRadius + 1.5f,
            center = center,
            style = Stroke(width = 1f),
        )

        drawCircle(lerp(dead, color, intensity), radius = lensRadius, center = center)
        if (intensity > 0.01f) {
            // The die itself, offset up-left toward the light like a real lens.
            drawCircle(
                color = lerp(color, Color.White, 0.45f).copy(alpha = intensity),
                radius = lensRadius * 0.42f,
                center = center - Offset(lensRadius * 0.22f, lensRadius * 0.24f),
            )
        }
    }
}

/** Lamp + legend, the way a state is annunciated on a real panel. */
@Composable
fun StatusIndicator(state: TunnelState, modifier: Modifier = Modifier) {
    val colors = PanelTheme.colors
    val (lampColor, labelRes) = when (state) {
        TunnelState.Disconnected -> colors.lampWhite to Res.string.status_disconnected
        TunnelState.Connecting -> colors.lampAmber to Res.string.status_connecting
        TunnelState.Connected -> colors.lampGreen to Res.string.status_connected
        TunnelState.Error -> colors.lampRed to Res.string.status_error
    }
    Row(
        modifier = modifier
            .machinedSurface(colors, corner = 2.dp, fill = colors.shellRaised)
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IndicatorLamp(
            color = lampColor,
            lit = state != TunnelState.Disconnected,
            pulsing = state == TunnelState.Connecting,
        )
        Text(
            stringResource(labelRes).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (state == TunnelState.Disconnected) colors.muted else lampColor,
            // An annunciator that wraps to two lines stops looking like one.
            maxLines = 1,
        )
    }
}

/** How far the bloom reaches past the lens, as a multiple of lamp size. */
private const val BLOOM_EXTENT = 2.6f
