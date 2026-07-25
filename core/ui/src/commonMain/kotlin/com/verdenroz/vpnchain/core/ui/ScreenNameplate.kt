package com.verdenroz.vpnchain.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme

/**
 * The badge struck into the enclosure. Every tab is another faceplate on the
 * same device, so they all carry the same nameplate treatment.
 */
@Composable
fun ScreenNameplate(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = PanelTheme.colors.readout,
        )
        trailing()
    }
}
