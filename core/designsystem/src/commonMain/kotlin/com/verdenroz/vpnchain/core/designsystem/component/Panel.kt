package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.theme.PanelColors
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme

/**
 * One lighting assumption for the whole enclosure: light arrives from above, so
 * an upward-facing edge catches it and a downward-facing one falls into shadow.
 * A raised surface reads light-on-top; a recess reads dark-on-top. Everything
 * else in the panel is built from these two.
 */
fun Modifier.machinedSurface(
    colors: PanelColors,
    corner: Dp = 3.dp,
    recessed: Boolean = false,
    fill: Color? = null,
): Modifier = clip(RoundedCornerShape(corner)).drawBehind {
    val radius = CornerRadius(corner.toPx())
    drawRoundRect(fill ?: if (recessed) colors.screenWell else colors.shellFace, cornerRadius = radius)

    // A pale alloy has less range between its tones than a dark one, so the
    // same alphas that model relief on graphite read as nothing at all here.
    val faceFalloff = if (colors.dark) 0.20f else 0.34f
    val underFalloff = if (colors.dark) 0.16f else 0.26f
    val edgeAlpha = if (colors.dark) 0.55f else 0.80f
    val outlineAlpha = if (colors.dark) {
        if (recessed) 0.95f else 0.70f
    } else {
        if (recessed) 0.75f else 0.45f
    }

    // Falloff across the face — a flat fill reads as paper, not metal.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to (if (recessed) colors.edgeShadow else colors.edgeHighlight).copy(alpha = faceFalloff),
            0.35f to Color.Transparent,
            1f to (if (recessed) colors.edgeHighlight else colors.edgeShadow).copy(alpha = underFalloff),
        ),
        cornerRadius = radius,
    )

    val inset = 0.5f
    val hairline = 1f
    // The two edges that actually sell the relief.
    drawLine(
        color = (if (recessed) colors.edgeShadow else colors.edgeHighlight).copy(alpha = edgeAlpha),
        start = Offset(corner.toPx(), inset),
        end = Offset(size.width - corner.toPx(), inset),
        strokeWidth = hairline,
    )
    drawLine(
        color = (if (recessed) colors.edgeHighlight else colors.edgeShadow).copy(alpha = edgeAlpha),
        start = Offset(corner.toPx(), size.height - inset),
        end = Offset(size.width - corner.toPx(), size.height - inset),
        strokeWidth = hairline,
    )
    drawRoundRect(
        color = colors.edgeShadow.copy(alpha = outlineAlpha),
        cornerRadius = radius,
        style = Stroke(width = hairline),
    )
}

/**
 * Text that reads as cut into the panel: the groove itself, plus the sliver of
 * light collecting on its lower lip. Two draws, one pixel apart.
 */
@Composable
fun EngravedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = PanelTheme.colors.engraved,
) {
    val colors = PanelTheme.colors
    Box(modifier) {
        Text(
            text,
            style = style,
            color = if (colors.dark) colors.edgeHighlight.copy(alpha = 0.45f) else colors.edgeHighlight,
            modifier = Modifier.offsetPx(dy = 1f),
        )
        Text(text, style = style, color = color)
    }
}

private fun Modifier.offsetPx(dx: Float = 0f, dy: Float = 0f): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(dx.toInt(), dy.toInt())
        }
    }
