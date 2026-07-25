package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.generated.resources.Res
import com.verdenroz.vpnchain.core.designsystem.generated.resources.switch_legend_off
import com.verdenroz.vpnchain.core.designsystem.generated.resources.switch_legend_on
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import org.jetbrains.compose.resources.stringResource

/**
 * The connect control as a throw switch: the paddle's position *is* the state,
 * so the panel can be read without reading any words. It travels with weight —
 * a long ease-out, no overshoot, the way something with mass actually stops.
 */
@Composable
fun ThrowSwitch(
    on: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val offLegend = stringResource(Res.string.switch_legend_off)
    val onLegend = stringResource(Res.string.switch_legend_on)

    val travel by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = ThrowEasing),
        label = "switch-travel",
    )
    val depress by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(90),
        label = "switch-depress",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SwitchHeight)
            .machinedSurface(colors, corner = 3.dp, recessed = true, fill = colors.shellDeep)
            .toggleable(
                value = on,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onValueChange = onToggle,
            )
            .padding(TrackInset),
    ) {
        val paddleWidth = maxWidth / 2

        // The legend the paddle is *not* covering — i.e. where a throw would
        // take you. The paddle itself always names the state it's in, because a
        // control that can be misread as the opposite state is how you end up
        // connecting a second time over a live tunnel.
        EngravedText(
            text = if (on) offLegend else onLegend,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier
                .align(if (on) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = LegendInset),
        )

        Box(
            Modifier
                .width(paddleWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = travel * (this.size.width)
                    // Pressing pushes the paddle into the panel rather than scaling it.
                    scaleY = 1f - depress * 0.04f
                    alpha = if (enabled) 1f else 0.55f
                }
                .machinedSurface(colors, corner = 2.dp, fill = colors.shellRaised)
                .knurl(),
            contentAlignment = Alignment.Center,
        ) {
            EngravedText(
                text = if (on) onLegend else offLegend,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) colors.lampGreen else colors.engraved,
            )
        }
    }
}

/** Ridges across the paddle — the one cue that says "this is for a thumb". */
@Composable
internal fun Modifier.knurl(): Modifier {
    val colors = PanelTheme.colors
    return drawWithContent {
        drawContent()
        val count = 4
        val spacing = 3.dp.toPx()
        val inset = size.width * 0.11f
        val top = size.height * 0.32f
        val bottom = size.height * 0.68f
        // Two grips, one either side of the legend — the ridges are where a
        // thumb would sit, not across the part you need to read.
        repeat(count) { i ->
            listOf(inset + i * spacing, size.width - inset - i * spacing).forEach { x ->
                drawLine(colors.edgeShadow.copy(alpha = 0.5f), Offset(x, top), Offset(x, bottom), 1f)
                drawLine(
                    colors.edgeHighlight.copy(alpha = 0.35f),
                    Offset(x + 1f, top),
                    Offset(x + 1f, bottom),
                    1f,
                )
            }
        }
    }
}

// Ease-out quint: fast throw, long settle, no bounce.
private val ThrowEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val SwitchHeight = 62.dp
private val TrackInset = 4.dp
private val LegendInset = 22.dp
