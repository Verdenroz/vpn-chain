package com.verdenroz.vpnchain.widget

import androidx.compose.ui.graphics.Color

/**
 * Frozen dark-panel palette from core/designsystem's PanelTheme. Widgets can't
 * read the Compose theme (RemoteViews surface), so the instrument look is
 * restated here; dark-first matches the design direction in `.impeccable.md`.
 */
internal object WidgetPalette {
    val shellFace = Color(0xFF1E1B17)
    val shellRaised = Color(0xFF2A2722)
    val readout = Color(0xFFEAE8E3)
    val muted = Color(0xFF868480)
    val lampGreen = Color(0xFF80D677)
    val lampAmber = Color(0xFFFAAB35)
    val lampRed = Color(0xFFEB4F3D)
}
