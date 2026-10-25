package com.verdenroz.vpnchain.feature.chain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.domain.ObserveOutsideChainSitesUseCase
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.OutsideChainSite
import com.verdenroz.vpnchain.core.model.SessionStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChainUiState(
    val status: ChainStatus = ChainStatus(),
    val hasProfile: Boolean = false,
    val route: ChainRoute = ChainRoute(),
    val stats: SessionStats = SessionStats(),
    val outsideChain: List<OutsideChainSite> = emptyList(),
)

class ChainViewModel(
    private val connectChain: ConnectChainUseCase,
    private val disconnectChain: DisconnectChainUseCase,
    observeChainRoute: ObserveChainRouteUseCase,
    observeOutsideChainSites: ObserveOutsideChainSitesUseCase,
    chainRepository: ChainRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val uiState: StateFlow<ChainUiState> =
        combine(
            chainRepository.status,
            profileRepository.profile.map { it != null },
            // Seeded empty because the route is measured: waiting for the first
            // probe would show a live tunnel as an unconfigured one.
            observeChainRoute().onStart { emit(ChainRoute()) },
            chainRepository.stats,
            observeOutsideChainSites(),
        ) { status, hasProfile, route, stats, outside ->
            ChainUiState(
                status = status,
                hasProfile = hasProfile,
                route = route,
                stats = stats,
                outsideChain = outside,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChainUiState(),
        )

    fun connect() = viewModelScope.launch { connectChain() }

    fun disconnect() = viewModelScope.launch { disconnectChain() }
}
