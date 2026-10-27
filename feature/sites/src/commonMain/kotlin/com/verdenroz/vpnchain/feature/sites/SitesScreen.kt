package com.verdenroz.vpnchain.feature.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.ActionButton
import com.verdenroz.vpnchain.core.designsystem.component.Edge
import com.verdenroz.vpnchain.core.designsystem.component.hairline
import com.verdenroz.vpnchain.core.designsystem.theme.VpnChainTheme
import com.verdenroz.vpnchain.core.ui.ScreenTitle
import com.verdenroz.vpnchain.core.ui.SectionCard
import com.verdenroz.vpnchain.feature.sites.generated.resources.Res
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_blocked_detail
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_blocked_title
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_direct_detail
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_direct_title
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_empty
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_entry_title
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_hidden_detail
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_remove
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_title
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_undo
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_use_my_connection
import com.verdenroz.vpnchain.feature.sites.generated.resources.sites_warp_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SitesRoute(
    modifier: Modifier = Modifier,
    viewModel: SitesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SitesScreen(uiState = uiState, onAction = viewModel::act, modifier = modifier)
}

@Composable
fun SitesScreen(
    uiState: SitesUiState,
    onAction: (SiteRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        ScreenTitle(title = stringResource(Res.string.sites_title))
        Spacer(Modifier.height(18.dp))
        if (uiState.isEmpty) {
            Text(
                stringResource(Res.string.sites_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = VpnChainTheme.colors.inkMuted,
            )
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SiteSection(Res.string.sites_blocked_title, Res.string.sites_blocked_detail, uiState.blocked, onAction)
            SiteSection(Res.string.sites_direct_title, Res.string.sites_direct_detail, uiState.direct, onAction)
            SiteSection(Res.string.sites_warp_title, Res.string.sites_hidden_detail, uiState.warp, onAction)
            SiteSection(Res.string.sites_entry_title, Res.string.sites_hidden_detail, uiState.entry, onAction)
        }
    }
}

@Composable
private fun SiteSection(
    title: StringResource,
    detail: StringResource,
    rows: List<SiteRow>,
    onAction: (SiteRow) -> Unit,
) {
    if (rows.isEmpty()) return
    val colors = VpnChainTheme.colors
    SectionCard(title = stringResource(title)) {
        Column {
            rows.forEachIndexed { index, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (index > 0) Modifier.hairline(Edge.Top, colors.line) else Modifier)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.host,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    ActionButton(text = stringResource(row.action.label()), onClick = { onAction(row) }, compact = true)
                }
            }
        }
    }
}

private fun SiteAction.label(): StringResource = when (this) {
    SiteAction.UseMyConnection -> Res.string.sites_use_my_connection
    SiteAction.Undo -> Res.string.sites_undo
    SiteAction.Remove -> Res.string.sites_remove
}
