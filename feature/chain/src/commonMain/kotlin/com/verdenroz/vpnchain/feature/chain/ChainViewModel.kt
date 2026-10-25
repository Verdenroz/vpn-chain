package com.verdenroz.vpnchain.feature.chain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
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
 * What the WARP tail and failing open take off the relay's exit. [outsideExit]
 * is how the [outsideCount] sites leave; one topology is dialled at a time, so
 * they all leave the same way.
 */
data class LearnedRouting(
    val warpMode: WarpMode = WarpMode.Off,
    val warpCount: Int = 0,
    val outsideCount: Int = 0,
    val outsideExit: OutsideExit? = null,
)

/** A WireGuard-only connection has no tail to reroute through, so it reads as off. */
internal fun learnedRouting(sites: List<LearnedSite>, settings: UserSettings, profile: SavedProfile?): LearnedRouting {
    if (profile?.supportsWarpExit != true) return LearnedRouting()
    val outside = sites.mapNotNull { it.outsideExit(settings, profile) }
    return LearnedRouting(
        warpMode = settings.warpMode,
        warpCount = if (settings.warpMode == WarpMode.Automatic) sites.count { it.route == LearnedRoute.Warp } else 0,
        outsideCount = outside.size,
        outsideExit = outside.firstOrNull(),
    )
}

class ChainViewModel(
    private val connectChain: ConnectChainUseCase,
    private val disconnectChain: DisconnectChainUseCase,
    observeChainRoute: ObserveChainRouteUseCase,
    chainRepository: ChainRepository,
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    learnedSites: LearnedSitesRepository,
) : ViewModel() {

    val uiState: StateFlow<ChainUiState> =
        combine(
            chainRepository.status,
            profileRepository.profile.map { it != null },
            // Seeded empty because the route is measured: waiting for the first
            // probe would show a live tunnel as an unconfigured one.
            observeChainRoute().onStart { emit(ChainRoute()) },
            chainRepository.stats.map { it.connectedSinceMillis }.distinctUntilChanged(),
            combine(learnedSites.sites, settingsRepository.settings, profileRepository.profile, ::learnedRouting)
                .distinctUntilChanged(),
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
}
