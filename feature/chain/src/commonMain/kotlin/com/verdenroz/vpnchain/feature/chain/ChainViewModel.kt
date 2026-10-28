package com.verdenroz.vpnchain.feature.chain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.domain.ChainRoute
import com.verdenroz.vpnchain.core.domain.ChainSupervisor
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.ObserveChainRouteUseCase
import com.verdenroz.vpnchain.core.domain.ReconnectState
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.bareHost
import com.verdenroz.vpnchain.core.model.offersOutside
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChainUiState(
    val status: ChainStatus = ChainStatus(),
    val hasProfile: Boolean = false,
    val route: ChainRoute = ChainRoute(),
    val connectedSinceMillis: Long? = null,
    val blockedSite: BlockedSite? = null,
    val cutOff: Boolean = false,
)

/** A site that refuses the chain and could go over the user's own connection; [host] is for display. */
data class BlockedSite(val domain: String, val host: String)

/**
 * The newest site waiting on the user's say-so to leave by the bare link, other
 * than ones [dismissed].
 */
internal fun blockedSite(
    sites: List<LearnedSite>,
    settings: UserSettings,
    profile: SavedProfile?,
    dismissed: Set<String>,
): BlockedSite? {
    if (!profile.offersOutside(settings)) return null
    val newest = sites
        .filter { it.route == LearnedRoute.DirectAvailable && it.domain !in dismissed }
        .maxByOrNull { it.learnedAtMillis }
        ?: return null
    return BlockedSite(newest.domain, bareHost(newest.domain, sites.mapTo(HashSet()) { it.domain }))
}

class ChainViewModel(
    private val connectChain: ConnectChainUseCase,
    private val disconnectChain: DisconnectChainUseCase,
    observeChainRoute: ObserveChainRouteUseCase,
    chainRepository: ChainRepository,
    private val profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    private val learnedSites: LearnedSitesRepository,
    supervisor: ChainSupervisor,
) : ViewModel() {

    private val dismissed = MutableStateFlow(emptySet<String>())

    val uiState: StateFlow<ChainUiState> =
        combine(
            chainRepository.status,
            profileRepository.profile.map { it != null },
            // Seeded empty because the route is measured: waiting for the first
            // probe would show a live tunnel as an unconfigured one.
            observeChainRoute().onStart { emit(ChainRoute()) },
            combine(
                chainRepository.stats.map { it.connectedSinceMillis }.distinctUntilChanged(),
                supervisor.state.map { it == ReconnectState.CutOff },
                ::Pair,
            ),
            combine(
                learnedSites.sites,
                settingsRepository.settings,
                profileRepository.profile,
                dismissed,
                ::blockedSite,
            ),
        ) { status, hasProfile, route, (connectedSince, cutOff), blockedSite ->
            ChainUiState(
                status = status,
                hasProfile = hasProfile,
                route = route,
                connectedSinceMillis = connectedSince,
                blockedSite = blockedSite,
                cutOff = cutOff,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChainUiState(),
        )

    fun connect() = viewModelScope.launch { connectChain() }

    fun disconnect() = viewModelScope.launch { disconnectChain() }

    fun removeConnection() = viewModelScope.launch {
        val id = profileRepository.activeProfileId.first() ?: return@launch
        disconnectChain()
        profileRepository.delete(id)
    }

    fun sendDirect(domain: String) = viewModelScope.launch { learnedSites.allowDirect(domain) }

    fun dismissBlocked(domain: String) = dismissed.update { it + domain }
}
