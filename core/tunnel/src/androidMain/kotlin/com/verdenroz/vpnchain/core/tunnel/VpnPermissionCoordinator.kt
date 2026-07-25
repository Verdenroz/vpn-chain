package com.verdenroz.vpnchain.core.tunnel

import android.content.Context
import android.net.VpnService

/**
 * Bridges the one-time `VpnService.prepare` consent (which must be launched from
 * an Activity) to the platform-agnostic connect flow. The Activity binds a
 * [requester]; the controller calls [ensure] before starting the tunnel.
 */
object VpnPermissionCoordinator {

    /** Returns true if consent was granted. Bound by the host Activity. */
    @Volatile
    private var requester: (suspend () -> Boolean)? = null

    fun bind(requester: suspend () -> Boolean) {
        this.requester = requester
    }

    fun unbind(requester: suspend () -> Boolean) {
        if (this.requester === requester) this.requester = null
    }

    /** Grants immediately if already consented; otherwise asks the Activity. */
    suspend fun ensure(context: Context): Boolean {
        if (VpnService.prepare(context) == null) return true
        return requester?.invoke() ?: false
    }
}
