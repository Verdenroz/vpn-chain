package com.verdenroz.vpnchain.core.data

import com.verdenroz.vpnchain.core.datastore.VpnChainPreferencesDataSource
import com.verdenroz.vpnchain.core.logging.Logger
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.warp.WarpRegistrar
import kotlinx.coroutines.flow.first

/**
 * Supplies the chain's WARP tail credentials, registering one the first time
 * and refreshing it before Cloudflare's TTL runs out.
 */
interface WarpRepository {
    /** @return credentials to render, or null when there are none to be had. */
    suspend fun exit(): WarpExit?
}

internal class DefaultWarpRepository(
    private val preferences: VpnChainPreferencesDataSource,
    private val registrar: WarpRegistrar,
    private val logger: Logger,
    private val now: () -> Long,
) : WarpRepository {

    override suspend fun exit(): WarpExit? {
        val stored = preferences.warpExit.first()
        if (stored != null && !stored.isStale(now())) return stored

        return registrar.register(now()).fold(
            onSuccess = { fresh -> fresh.also { preferences.setWarpExit(it) } },
            onFailure = { failure ->
                // Refresh runs a month before Cloudflare's own expiry, so a key
                // we call stale is usually still good — and the usual reason
                // registration fails is a network that can't reach Cloudflare
                // at all, which says nothing about the key already held.
                logger.w(TAG, "WARP registration failed: ${failure.message}")
                stored
            },
        )
    }

    private companion object {
        const val TAG = "WarpRepository"
    }
}
