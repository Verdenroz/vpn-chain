package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.verdenroz.vpnchain.core.designsystem.generated.resources.Res
import com.verdenroz.vpnchain.core.designsystem.generated.resources.archivo_variable
import com.verdenroz.vpnchain.core.designsystem.generated.resources.martian_mono_variable
import org.jetbrains.compose.resources.Font

/**
 * Archivo — a grotesque cut for high-density print, with a width axis. Used
 * narrow for engraved panel labels and normal for everything else, so the
 * silkscreen and the prose come off the same tool.
 */
@Composable
private fun archivo(width: Float): FontFamily {
    val regular = variableFont(Res.font.archivo_variable, FontWeight.Normal, 400f, width)
    val medium = variableFont(Res.font.archivo_variable, FontWeight.Medium, 500f, width)
    val semiBold = variableFont(Res.font.archivo_variable, FontWeight.SemiBold, 600f, width)
    val bold = variableFont(Res.font.archivo_variable, FontWeight.Bold, 700f, width)
    return remember(regular, medium, semiBold, bold) {
        FontFamily(regular, medium, semiBold, bold)
    }
}

/**
 * Martian Mono — wide, squared, mechanical. Reserved for values a machine
 * produced: addresses, round-trip times, counters, log lines.
 */
@Composable
private fun martianMono(width: Float = MONO_WIDTH): FontFamily {
    val regular = variableFont(Res.font.martian_mono_variable, FontWeight.Normal, 400f, width)
    val medium = variableFont(Res.font.martian_mono_variable, FontWeight.Medium, 500f, width)
    return remember(regular, medium) { FontFamily(regular, medium) }
}

@Composable
private fun variableFont(
    resource: org.jetbrains.compose.resources.FontResource,
    weight: FontWeight,
    weightAxis: Float,
    widthAxis: Float,
) = Font(
    resource = resource,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weightAxis.toInt()),
        FontVariation.width(widthAxis),
    ),
)

/**
 * Five sizes, ≥1.25× apart, so hierarchy survives being glanced at. Panel
 * labels are small and tracked wide — engraved text is cut shallow and spaced
 * out to stay legible, and copying that reads as manufactured.
 */
@Composable
internal fun vpnChainTypography(): Typography {
    val panel = archivo(PANEL_WIDTH)
    val label = archivo(LABEL_WIDTH)
    val mono = martianMono()
    val base = Typography()
    return remember(panel, label, mono) {
        base.copy(
            // Device nameplate.
            headlineMedium = base.headlineMedium.copy(
                fontFamily = label,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                letterSpacing = 0.14.em,
            ),
            // Section engraving: "ROUTE", "CHAIN".
            titleMedium = base.titleMedium.copy(
                fontFamily = label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.22.em,
            ),
            bodyLarge = base.bodyLarge.copy(
                fontFamily = panel,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 23.sp,
            ),
            bodyMedium = base.bodyMedium.copy(
                fontFamily = panel,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
            // Machine-produced values.
            bodySmall = base.bodySmall.copy(
                fontFamily = mono,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                letterSpacing = (-0.02).em,
            ),
            // Switch legends and row labels.
            labelLarge = base.labelLarge.copy(
                fontFamily = label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.18.em,
            ),
            labelSmall = base.labelSmall.copy(
                fontFamily = label,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 0.20.em,
            ),
        )
    }
}

/** Inline technical readouts outside the type-scale slots (IPs, RTT, log body). */
@Composable
fun monospaceTextStyle(): TextStyle {
    val mono = martianMono()
    return remember(mono) { TextStyle(fontFamily = mono, letterSpacing = (-0.02).em) }
}

// Archivo's width axis: labels are drawn condensed like silkscreen, running
// text sits at normal width. Martian Mono is narrowed so addresses fit a row.
private const val PANEL_WIDTH = 100f
private const val LABEL_WIDTH = 87f
private const val MONO_WIDTH = 87.5f
