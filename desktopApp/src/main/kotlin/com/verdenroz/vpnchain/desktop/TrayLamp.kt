package com.verdenroz.vpnchain.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import com.verdenroz.vpnchain.core.model.TunnelState

/**
 * The tray icon: the panel's indicator lamp, shrunk to a tray slot.
 *
 * Drawn rather than shipped as four PNGs so the states stay in step with the
 * lamp colors used everywhere else, and so it scales to whatever size the tray
 * asks for.
 */
internal class TrayLamp(private val state: TunnelState) : Painter() {

    override val intrinsicSize = Size(ICON_EXTENT, ICON_EXTENT)

    override fun DrawScope.onDraw() {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val lamp = state.lampColor()

        // A dark bezel keeps the lamp legible on both light and dark trays,
        // which we don't get to know the color of.
        drawCircle(color = BEZEL, radius = radius, center = center)
        drawCircle(color = lamp, radius = radius * LAMP_RATIO, center = center)
        if (state == TunnelState.Connected) {
            drawCircle(color = lamp.copy(alpha = 0.35f), radius = radius * BLOOM_RATIO, center = center)
        }
    }

    private companion object {
        const val ICON_EXTENT = 64f
        const val LAMP_RATIO = 0.62f
        const val BLOOM_RATIO = 0.85f
        val BEZEL = Color(0xFF14181B)
    }
}

/** Same phosphor vocabulary as the on-panel lamps. */
internal fun TunnelState.lampColor(): Color = when (this) {
    TunnelState.Connected -> Color(0xFF34D399)
    TunnelState.Connecting -> Color(0xFFFBBF24)
    TunnelState.Error -> Color(0xFFEF4444)
    TunnelState.Disconnected -> Color(0xFF6B7280)
}
