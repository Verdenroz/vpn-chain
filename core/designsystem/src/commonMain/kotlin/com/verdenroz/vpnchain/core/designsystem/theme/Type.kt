package com.verdenroz.vpnchain.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.verdenroz.vpnchain.core.designsystem.generated.resources.Res
import com.verdenroz.vpnchain.core.designsystem.generated.resources.hanken_grotesk_bold
import com.verdenroz.vpnchain.core.designsystem.generated.resources.hanken_grotesk_medium
import com.verdenroz.vpnchain.core.designsystem.generated.resources.hanken_grotesk_regular
import com.verdenroz.vpnchain.core.designsystem.generated.resources.hanken_grotesk_semibold
import org.jetbrains.compose.resources.Font

@Composable
private fun hankenGrotesk(): FontFamily {
    val regular = Font(Res.font.hanken_grotesk_regular, FontWeight.Normal)
    val medium = Font(Res.font.hanken_grotesk_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.hanken_grotesk_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.hanken_grotesk_bold, FontWeight.Bold)
    return remember(regular, medium, semiBold, bold) { FontFamily(regular, medium, semiBold, bold) }
}

/** Hanken Grotesk throughout; digits are tabular so readouts don't jitter as they tick. */
@Composable
internal fun vpnChainTypography(): Typography {
    val family = hankenGrotesk()
    val base = Typography()
    return remember(family) {
        base.copy(
            headlineMedium = base.headlineMedium.copy(
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.01).em,
            ),
            titleLarge = base.titleLarge.copy(
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
            titleMedium = base.titleMedium.copy(
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.02.em,
            ),
            titleSmall = base.titleSmall.copy(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            bodyLarge = base.bodyLarge.copy(
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
            bodyMedium = base.bodyMedium.copy(
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            bodySmall = base.bodySmall.copy(
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = "tnum",
            ),
            labelLarge = base.labelLarge.copy(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
            labelMedium = base.labelMedium.copy(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            labelSmall = base.labelSmall.copy(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
        )
    }
}
