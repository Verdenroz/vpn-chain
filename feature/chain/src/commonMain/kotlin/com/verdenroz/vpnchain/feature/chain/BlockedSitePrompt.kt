package com.verdenroz.vpnchain.feature.chain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.ActionTone
import com.verdenroz.vpnchain.core.designsystem.component.InfoCard
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.feature.chain.generated.resources.Res
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_blocked_detail
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_blocked_dismiss
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_blocked_title
import com.verdenroz.vpnchain.feature.chain.generated.resources.chain_blocked_use
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BlockedSitePrompt(
    site: BlockedSite,
    onDismiss: (String) -> Unit,
    onUseMyConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VpnChainTheme.colors
    InfoCard(modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.chain_blocked_title, site.host),
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.chain_blocked_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ActionButton(
                stringResource(Res.string.chain_blocked_dismiss),
                onClick = { onDismiss(site.domain) },
                tone = ActionTone.Plain,
                compact = true,
            )
            ActionButton(
                stringResource(Res.string.chain_blocked_use),
                onClick = { onUseMyConnection(site.domain) },
                tone = ActionTone.Rerouted,
                compact = true,
            )
        }
    }
}
