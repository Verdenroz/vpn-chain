package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
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

/** Panel tokens, read as `PanelTheme.colors` at any call site. */
object PanelTheme {
    val colors: PanelColors
        @Composable get() = LocalPanelColors.current
}

private fun PanelColors.toMaterialScheme() = if (dark) {
    darkColorScheme(
        background = shellDeep,
        onBackground = readout,
        surface = shellFace,
        onSurface = readout,
        surfaceVariant = shellRaised,
        onSurfaceVariant = muted,
        outline = edgeHighlight,
        outlineVariant = edgeShadow,
        primary = lampGreen,
        onPrimary = shellDeep,
        secondary = lampAmber,
        onSecondary = shellDeep,
        tertiary = lampWhite,
        error = lampRed,
        onError = shellDeep,
    )
} else {
    lightColorScheme(
        background = shellDeep,
        onBackground = readout,
        surface = shellFace,
        onSurface = readout,
        surfaceVariant = shellRaised,
        onSurfaceVariant = muted,
        outline = edgeShadow,
        outlineVariant = edgeShadow,
        primary = lampGreen,
        onPrimary = edgeHighlight,
        secondary = lampAmber,
        onSecondary = edgeHighlight,
        tertiary = lampWhite,
        error = lampRed,
        onError = edgeHighlight,
    )
}

@Composable
fun VpnChainTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val panel = if (darkTheme) DarkPanel else LightPanel
    CompositionLocalProvider(LocalPanelColors provides panel) {
        MaterialTheme(
            colorScheme = panel.toMaterialScheme(),
            typography = vpnChainTypography(),
            content = content,
        )
    }
}
