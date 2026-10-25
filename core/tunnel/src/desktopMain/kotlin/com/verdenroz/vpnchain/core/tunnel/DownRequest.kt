package com.verdenroz.vpnchain.core.tunnel

import java.io.File

/** True exactly once per request: deleting the marker is what claims it. */
internal fun consumeDownRequest(marker: File): Boolean = marker.delete()
