package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.OriginRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.geoip.NetworkProbe
import com.verdenroz.vpnchain.core.geoip.RouteGeolocator
import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.effectiveFor
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest

/**
 * The live route, re-resolved as connection state changes and on a slow tick.
 * Nothing is taken from the profile where a measurement is available instead.
 *
 * Stateful (it caches and throttles its own probing), so keep it a singleton.
 */
class ObserveChainRouteUseCase(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val chainRepository: ChainRepository,
    private val originRepository: OriginRepository,
    private val geolocator: RouteGeolocator,
    private val probe: NetworkProbe,
    private val now: () -> Long = ::currentTimeMillis,
) {
    /** What the last probe saw, and the path it saw it over. */
    private class Sample(
        val ip: String,
        val elapsedMs: Int,
        val throughChain: Boolean,
        val atMs: Long,
    )

    private var lastSample: Sample? = null

    /** The topology as dialed, not as stored — a disabled entry hop is not a hop. */
    private val effectiveProfile: Flow<ChainProfile?> =
        combine(profileRepository.profile, settingsRepository.settings) { profile, settings ->
            profile?.effectiveFor(settings)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ChainRoute> =
        combine(
            effectiveProfile,
            chainRepository.status,
            originRepository.lastKnownOriginIp,
            samples(),
            settingsRepository.settings,
        ) { profile, status, lastOrigin, sample, settings ->
            Inputs(profile, status, lastOrigin, sample, settings)
        }
            .mapLatest { render(it) }

    private data class Inputs(
        val profile: ChainProfile?,
        val status: ChainStatus,
        val lastOrigin: String?,
        val sample: Sample?,
        val settings: UserSettings,
    )

    /**
     * Probing runs on its own timer, restarted only when the path itself
     * changes. A refresh tick that cancelled the request in flight never
     * completed one on a slow link — which is exactly when the tunnel is
     * warming up and the readout is worth having.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun samples(): Flow<Sample?> =
        combine(
            chainRepository.status.map { it.state == TunnelState.Connected }.distinctUntilChanged(),
            effectiveProfile,
        ) { connected, profile -> connected to profile }
            .transformLatest { (connected, profile) ->
                while (true) {
                    emit(currentSample(connected, profile))
                    delay(SAMPLE_INTERVAL_MS)
                }
            }

    private suspend fun render(inputs: Inputs): ChainRoute {
        val connected = inputs.status.state == TunnelState.Connected
        val sample = inputs.sample
        return ChainRoute(
            hops = listOfNotNull(
                originHop(connected, sample, inputs.lastOrigin, inputs.profile, inputs.status),
                entryHop(inputs.profile, connected),
                exitHop(inputs.profile, inputs.status, sample, connected, inputs.settings),
            ),
            // Only report a timing taken over the path currently in use.
            throughRttMs = sample?.takeIf { it.throughChain == connected }?.elapsedMs,
        )
    }

    /**
     * One request answers both questions worth asking — what address the far
     * end sees, and how long the round trip takes — so they can never disagree.
     * Re-probed immediately when the path changes, and otherwise throttled: a
     * public IP changes far more slowly than the panel refreshes.
     */
    private suspend fun currentSample(connected: Boolean, profile: ChainProfile?): Sample? {
        val cached = lastSample
        val takenAt = now()
        val stillGood = cached != null &&
            cached.throughChain == connected &&
            takenAt - cached.atMs < SAMPLE_INTERVAL_MS
        if (stillGood) return cached

        val taken = probe.samplePublicIp()?.let { Sample(it.ip, it.elapsedMs, connected, takenAt) }
            ?: return cached?.takeIf { it.throughChain == connected }
        lastSample = taken
        // Guard against recording the relay's own address as this machine's:
        // a tunnel that came up outside the app can be live before the status
        // catches up, and that answer would be indistinguishable otherwise.
        if (!connected && taken.ip != profile?.vpsIp) originRepository.record(taken.ip)
        return taken
    }

    /** Traffic always starts here, so this hop carries in every state. */
    private suspend fun originHop(
        connected: Boolean,
        sample: Sample?,
        lastOrigin: String?,
        profile: ChainProfile?,
        status: ChainStatus,
    ): ChainHop {
        // Probing while connected only re-reads the exit — hiding this address
        // is the entire point of the chain, so it gets recalled instead.
        val measured = sample?.takeIf { !connected && !it.throughChain }?.ip
        // A recalled value that matches an exit isn't an origin, whatever the
        // store says: it was captured through a tunnel the app hadn't noticed.
        val recalled = lastOrigin?.takeIf { it != profile?.vpsIp && it != status.exitIp }
        val ip = measured ?: recalled
        return ChainHop(
            role = HopRole.Origin,
            ip = ip,
            location = ip?.let { geolocator.locate(it) },
            evidence = when {
                measured != null -> HopEvidence.Measured
                ip != null -> HopEvidence.Recalled
                else -> HopEvidence.Unknown
            },
            carrying = true,
        )
    }

    /** Absent entirely on a relay-only chain — there is no second hop to draw. */
    private suspend fun entryHop(profile: ChainProfile?, connected: Boolean): ChainHop? {
        val entry = profile?.entryHop ?: return null
        return ChainHop(
            role = HopRole.Entry,
            ip = entry.endpointHost,
            location = geolocator.locate(entry.endpointHost),
            evidence = HopEvidence.Configured,
            carrying = connected,
            via = "wireguard · udp ${entry.endpointPort}",
        )
    }

    private suspend fun exitHop(
        profile: ChainProfile?,
        status: ChainStatus,
        sample: Sample?,
        connected: Boolean,
        settings: UserSettings,
    ): ChainHop? {
        // While connected, our own sample *is* the exit as the world sees it.
        val measured = sample?.takeIf { connected && it.throughChain }?.ip ?: status.exitIp
        val ip = measured ?: profile?.vpsIp ?: return null
        // Named only on evidence: the tail is configured *and* the address the
        // world reports is not the relay's. Registration can fail silently, and
        // a hop that isn't carrying must never be drawn as though it were.
        val tailCarrying = settings.warpMode != WarpMode.Off &&
            measured != null && measured != profile?.vpsIp
        return ChainHop(
            role = HopRole.Exit,
            ip = ip,
            location = geolocator.locate(ip),
            evidence = if (measured != null) HopEvidence.Measured else HopEvidence.Configured,
            carrying = connected,
            via = profile?.let {
                if (tailCarrying) "vless · reality → warp" else "vless · reality · tcp ${it.serverPort}"
            },
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 10_000L
    }
}
