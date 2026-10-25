package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.BlockReason
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.OutsideExit
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_age_days
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_age_hours
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_age_minutes
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_age_now
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_both
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_direct
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_entry
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_everywhere
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_remove
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_title
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_learned_warp
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_reason_challenge
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_reason_forbidden
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_reason_legal_block
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_reason_network_security
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_reason_rate_limited
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The sites detection found, newest first, each with a way to drop it. */
@Composable
internal fun LearnedSites(
    sites: List<LearnedSite>,
    exits: Map<String, OutsideExit>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sites.isEmpty()) return
    val colors = PanelTheme.colors
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(Res.string.settings_learned_title),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.readout,
        )
        sites.forEach { site ->
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        site.domain,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.readout,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        learnedDetail(site, exits[site.domain]),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                PanelButton(
                    text = stringResource(Res.string.settings_learned_remove),
                    onClick = { onRemove(site.domain) },
                )
            }
        }
    }
}

@Composable
private fun learnedDetail(site: LearnedSite, exit: OutsideExit?): String {
    val reason = stringResource(site.reason.label())
    val age = ageText(currentTimeMillis() - site.learnedAtMillis)
    return when (site.route) {
        LearnedRoute.Warp -> stringResource(Res.string.settings_learned_warp, reason, age)
        LearnedRoute.RefusedByBoth -> stringResource(Res.string.settings_learned_both, reason, age)
        LearnedRoute.FailOpen -> when (exit) {
            OutsideExit.EntryHop -> stringResource(Res.string.settings_learned_entry, reason, age)
            OutsideExit.Direct -> stringResource(Res.string.settings_learned_direct, reason, age)
            null -> stringResource(Res.string.settings_learned_both, reason, age)
        }
        LearnedRoute.RefusedEverywhere -> stringResource(Res.string.settings_learned_everywhere, reason, age)
    }
}

@Composable
private fun ageText(elapsedMillis: Long): String {
    val minutes = elapsedMillis / MINUTE
    return when {
        minutes < 1 -> stringResource(Res.string.settings_learned_age_now)
        minutes < 60 -> stringResource(Res.string.settings_learned_age_minutes, minutes)
        minutes < 24 * 60 -> stringResource(Res.string.settings_learned_age_hours, minutes / 60)
        else -> stringResource(Res.string.settings_learned_age_days, minutes / (24 * 60))
    }
}

private fun BlockReason.label(): StringResource = when (this) {
    BlockReason.Forbidden -> Res.string.settings_reason_forbidden
    BlockReason.RateLimited -> Res.string.settings_reason_rate_limited
    BlockReason.LegalBlock -> Res.string.settings_reason_legal_block
    BlockReason.NetworkSecurity -> Res.string.settings_reason_network_security
    BlockReason.Challenge -> Res.string.settings_reason_challenge
}

private const val MINUTE = 60_000L
