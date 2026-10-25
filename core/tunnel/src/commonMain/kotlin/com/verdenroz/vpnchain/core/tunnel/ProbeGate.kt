package com.verdenroz.vpnchain.core.tunnel

import kotlinx.coroutines.flow.Flow

/** Whether this is a good moment to spend radio time on background probes. */
interface ProbeGate {
    val open: Flow<Boolean>
}
