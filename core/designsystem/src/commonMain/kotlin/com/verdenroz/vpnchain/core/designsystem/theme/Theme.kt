package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The enclosure's own tokens. Material has slots for text and fills but none
 * for a machined edge or a lamp phosphor, and those are the two things this
 * interface is made of.
 */
@Immutable
data class PanelColors(
    /** Ground the panel sits on — the deepest recess. */
    val shellDeep: Color,
    /** The panel face itself. */
    val shellFace: Color,
    /** A band raised proud of the face. */
    val shellRaised: Color,
    /** Light catching a top edge; the panel is lit from above-left throughout. */
    val edgeHighlight: Color,
    /** Shadow under an edge or in a seam. */
    val edgeShadow: Color,
    /** Backlight ground of the inset display — a different material to the shell. */
    val screenWell: Color,
    /** Landmass fill on the display. */
    val screenLand: Color,
    val readout: Color,
    val engraved: Color,
    val muted: Color,
    val lampGreen: Color,
    val lampAmber: Color,
    val lampRed: Color,
    val lampWhite: Color,
    /** Lamps bloom onto a dark panel and can only stain a pale one. */
    val dark: Boolean,
)

private val DarkPanel = PanelColors(
    shellDeep = ShellDeepDark,
    shellFace = ShellFaceDark,
    shellRaised = ShellRaisedDark,
    edgeHighlight = ShellEdgeHiDark,
    edgeShadow = ShellEdgeLoDark,
    screenWell = ScreenWellDark,
    screenLand = ScreenLandDark,
    readout = TextReadoutDark,
    engraved = TextEngravedDark,
    muted = TextMutedDark,
    lampGreen = LampGreenDark,
    lampAmber = LampAmberDark,
    lampRed = LampRedDark,
    lampWhite = LampWhiteDark,
    dark = true,
)

private val LightPanel = PanelColors(
    shellDeep = ShellDeepLight,
    shellFace = ShellFaceLight,
    shellRaised = ShellRaisedLight,
    edgeHighlight = ShellEdgeHiLight,
    edgeShadow = ShellEdgeLoLight,
    screenWell = ScreenWellLight,
    screenLand = ScreenLandLight,
    readout = TextReadoutLight,
    engraved = TextEngravedLight,
    muted = TextMutedLight,
    lampGreen = LampGreenLight,
    lampAmber = LampAmberLight,
    lampRed = LampRedLight,
    lampWhite = LampWhiteLight,
    dark = false,
)

val LocalPanelColors = staticCompositionLocalOf { DarkPanel }

private val LocalVpnChainColors = staticCompositionLocalOf { DarkVpnChainColors }

/** Panel tokens, read as `PanelTheme.colors` at any call site. */
object PanelTheme {
    val colors: PanelColors
        @Composable get() = LocalPanelColors.current
}

@Composable
fun VpnChainTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val panel = if (darkTheme) DarkPanel else LightPanel
    val colors = if (darkTheme) DarkVpnChainColors else LightVpnChainColors
    CompositionLocalProvider(LocalPanelColors provides panel, LocalVpnChainColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = vpnChainTypography(),
            content = content,
        )
    }
}

object VpnChainTheme {
    val colors: VpnChainColors
        @Composable @ReadOnlyComposable
        get() = LocalVpnChainColors.current
}
