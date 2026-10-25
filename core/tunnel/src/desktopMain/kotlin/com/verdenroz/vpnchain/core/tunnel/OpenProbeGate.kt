package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class OpenProbeGate : ProbeGate {
    override val open: Flow<Boolean> = flowOf(true)
}
