package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme

private val CardShape = RoundedCornerShape(16.dp)

/** A grouped surface. Light theme outlines it; on dark the fill step is enough. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = VpnChainTheme.colors
    Column(
        modifier
            .clip(CardShape)
            .background(colors.surface)
            .then(if (colors.isDark) Modifier else Modifier.border(1.dp, colors.line, CardShape))
            .padding(20.dp),
        content = content,
    )
}
