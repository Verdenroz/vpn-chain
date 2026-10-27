package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme

enum class ActionTone { Outlined, Plain, Primary, Danger, Rerouted }

private val ButtonShape = RoundedCornerShape(10.dp)

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ActionTone = ActionTone.Outlined,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = VpnChainTheme.colors
    val fill = when (tone) {
        ActionTone.Outlined, ActionTone.Plain -> null
        ActionTone.Primary -> colors.connected
        ActionTone.Danger -> colors.disconnect
        ActionTone.Rerouted -> colors.rerouted
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(if (compact) 40.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(ButtonShape)
            .then(
                when {
                    fill != null -> Modifier.background(fill)
                    tone == ActionTone.Plain -> Modifier
                    else -> Modifier.border(BorderStroke(1.dp, colors.outline), ButtonShape)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            color = when {
                tone == ActionTone.Rerouted -> colors.onRerouted
                fill != null -> colors.onFilled
                else -> colors.ink
            },
            maxLines = 1,
        )
    }
}
