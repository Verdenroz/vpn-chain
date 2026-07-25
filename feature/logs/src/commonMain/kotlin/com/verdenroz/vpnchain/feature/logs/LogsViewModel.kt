package com.verdenroz.vpnchain.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.LogRepository
import com.verdenroz.vpnchain.core.model.LogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LogsViewModel(
    private val logRepository: LogRepository,
) : ViewModel() {

    val entries: StateFlow<List<LogEntry>> =
        logRepository.entries.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = logRepository.entries.value,
        )

    fun clear() = logRepository.clear()
}
