package com.verdenroz.vpnchain.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.InfoCard
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme

/** A titled group: the name sits above a card holding the contents. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel(title)
        Spacer(Modifier.height(8.dp))
        InfoCard(Modifier.fillMaxWidth()) {
            CompositionLocalProvider(
                LocalContentColor provides VpnChainTheme.colors.ink,
                content = content,
            )
        }
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = VpnChainTheme.colors.inkMuted,
    )
}
