package com.verdenroz.vpnchain.feature.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdenroz.vpnchain.core.designsystem.component.IndicatorLamp
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.core.designsystem.component.machinedSurface
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme
import com.verdenroz.vpnchain.core.model.LogEntry
import com.verdenroz.vpnchain.core.ui.ScreenNameplate
import com.verdenroz.vpnchain.core.ui.SectionLabel
import com.verdenroz.vpnchain.feature.logs.generated.resources.Res
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_clear
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_empty_body
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_empty_title
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_line_count
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_no_lines
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_output_label
import com.verdenroz.vpnchain.feature.logs.generated.resources.logs_screen_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LogsRoute(
    modifier: Modifier = Modifier,
    viewModel: LogsViewModel = koinViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    LogsScreen(entries = entries, onClear = viewModel::clear, modifier = modifier)
}

/**
 * The output well: a recessed screen the tunnel core prints into. The lamp
 * tracks whether anything is arriving, so a silent chain is distinguishable
 * from a stalled one at a glance.
 */
@Composable
fun LogsScreen(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    val listState = rememberLazyListState()
    // Follow the tail only while the tail is what you're looking at. Scrolling
    // back through a live tunnel's history is otherwise impossible — every new
    // line would drag the view back down.
    val following by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && following) listState.animateScrollToItem(entries.lastIndex)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.shellDeep)
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        ScreenNameplate(title = stringResource(Res.string.logs_screen_title)) {
            IndicatorLamp(color = colors.lampAmber, lit = entries.isNotEmpty(), size = 9.dp)
        }

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .machinedSurface(colors, corner = 4.dp)
                .padding(16.dp),
        ) {
            SectionLabel(stringResource(Res.string.logs_output_label))
            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .machinedSurface(colors, corner = 2.dp, recessed = true, fill = colors.screenWell)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (entries.isEmpty()) {
                    EmptyWell()
                } else {
                    SelectionContainer {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(entries) { entry -> LogLine(entry) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (entries.isEmpty()) {
                        stringResource(Res.string.logs_no_lines)
                    } else {
                        stringResource(Res.string.logs_line_count, entries.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                PanelButton(text = stringResource(Res.string.logs_clear), onClick = onClear, enabled = entries.isNotEmpty())
            }
        }
    }
}

/** Time and severity are fixed-width and quiet; the message is what you came to read. */
@Composable
private fun LogLine(entry: LogEntry) {
    val colors = PanelTheme.colors
    val parsed = remember(entry.message) { parseLogLine(entry.message) }
    val levelColor = when (parsed.level) {
        "ERROR", "FATAL", "PANIC" -> colors.lampRed
        "WARN", "WARNING" -> colors.lampAmber
        else -> colors.muted
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            formatLogTime(entry.timestampMillis),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.widthIn(min = TimeColumnWidth),
        )
        Spacer(Modifier.widthIn(min = 10.dp))
        Text(
            parsed.level.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = levelColor,
            modifier = Modifier.widthIn(min = LevelColumnWidth),
        )
        Text(
            parsed.body,
            style = MaterialTheme.typography.bodySmall,
            color = if (parsed.level == null) colors.lampWhite else colors.readout,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyWell() {
    val colors = PanelTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            stringResource(Res.string.logs_empty_title),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(Res.string.logs_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
    }
}

private val TimeColumnWidth = 74.dp
private val LevelColumnWidth = 52.dp
