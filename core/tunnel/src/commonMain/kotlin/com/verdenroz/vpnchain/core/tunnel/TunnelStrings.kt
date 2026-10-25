package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.tunnel.generated.resources.Res
import com.verdenroz.vpnchain.core.tunnel.generated.resources.allStringResources
import org.jetbrains.compose.resources.StringResource

/** One of this module's strings by its resource key, for status text that arrived as a key from another process. */
fun tunnelString(key: String): StringResource? = Res.allStringResources[key]
