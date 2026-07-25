package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.model.LogEntry
import com.verdenroz.vpnchain.core.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Keeps a bounded, in-memory ring buffer of tunnel output for the log screen. */
interface LogRepository {
    val entries: StateFlow<List<LogEntry>>
    fun clear()
}

internal class DefaultLogRepository(
    controller: TunnelController,
    scope: CoroutineScope,
    private val now: () -> Long,
    private val capacity: Int = 500,
) : LogRepository {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            controller.logs.collect { line ->
                _entries.update { current ->
                    (current + LogEntry(now(), line)).takeLast(capacity)
                }
            }
        }
    }

    override fun clear() = _entries.update { emptyList() }
}
