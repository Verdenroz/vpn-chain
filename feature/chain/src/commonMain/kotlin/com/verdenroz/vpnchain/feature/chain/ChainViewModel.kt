package com.verdenroz.vpnchain.feature.chain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.data.WarpRepository
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.OutsideExit
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.outsideExit
import com.verdenroz.vpnchain.core.model.outsideExitFor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChainUiState(
    val status: ChainStatus = ChainStatus(),
    val hasProfile: Boolean = false,
    val route: ChainRoute = ChainRoute(),
    val connectedSinceMillis: Long? = null,
    val routing: LearnedRouting = LearnedRouting(),
)

/**
 * What the WARP tail and failing open take off the relay's exit. [offered] are
 * sites the relay and WARP refused that the user may send down the bare link,
 * and [sentDirect] the ones they did; both exist only where there is no entry hop.
 */
data class LearnedRouting(
    val warpMode: WarpMode = WarpMode.Off,
    val warpCount: Int = 0,
    val entryHopCount: Int = 0,
    val offered: List<String> = emptyList(),
    val sentDirect: List<String> = emptyList(),
)

/**
 * A WireGuard-only connection has no tail to reroute through, and without a
 * registration none is rendered, so both read as off.
 */
internal fun learnedRouting(
    sites: List<LearnedSite>,
    settings: UserSettings,
    profile: SavedProfile?,
    warpRegistered: Boolean,
): LearnedRouting {
    if (profile?.supportsWarpExit != true) return LearnedRouting()
    val warpMode = if (warpRegistered) settings.warpMode else WarpMode.Off
    val exit = profile.outsideExitFor(settings)
    fun domains(route: LearnedRoute) = sites.filter { it.route == route }.map { it.domain }
    return LearnedRouting(
        warpMode = warpMode,
        warpCount = if (warpMode == WarpMode.Automatic) sites.count { it.route == LearnedRoute.Warp } else 0,
        entryHopCount = sites.count { it.outsideExit(settings, profile) == OutsideExit.EntryHop },
        offered = if (exit == OutsideExit.Direct) domains(LearnedRoute.DirectAvailable) else emptyList(),
        sentDirect = if (exit == OutsideExit.Direct) domains(LearnedRoute.Direct) else emptyList(),
    )
}

class ChainViewModel(
    private val connectChain: ConnectChainUseCase,
    private val disconnectChain: DisconnectChainUseCase,
    observeChainRoute: ObserveChainRouteUseCase,
    chainRepository: ChainRepository,
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    private val learnedSites: LearnedSitesRepository,
    warpRepository: WarpRepository,
) : ViewModel() {

    val uiState: StateFlow<ChainUiState> =
        combine(
            chainRepository.status,
            profileRepository.profile.map { it != null },
            // Seeded empty because the route is measured: waiting for the first
            // probe would show a live tunnel as an unconfigured one.
            observeChainRoute().onStart { emit(ChainRoute()) },
            chainRepository.stats.map { it.connectedSinceMillis }.distinctUntilChanged(),
            combine(
                learnedSites.sites,
                settingsRepository.settings,
                profileRepository.profile,
                warpRepository.registered,
                ::learnedRouting,
            ),
        ) { status, hasProfile, route, connectedSince, routing ->
            ChainUiState(
                status = status,
                hasProfile = hasProfile,
                route = route,
                connectedSinceMillis = connectedSince,
                routing = routing,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChainUiState(),
        )

    fun connect() = viewModelScope.launch { connectChain() }

    fun disconnect() = viewModelScope.launch { disconnectChain() }

    fun sendDirect(domain: String) = viewModelScope.launch { learnedSites.allowDirect(domain) }

    fun undoDirect(domain: String) = viewModelScope.launch { learnedSites.forget(domain) }
}
