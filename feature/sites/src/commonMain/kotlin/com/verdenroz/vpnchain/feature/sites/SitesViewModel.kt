package com.verdenroz.vpnchain.feature.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.data.WarpRepository
import com.verdenroz.vpnchain.core.model.LearnedRoute
import com.verdenroz.vpnchain.core.model.LearnedSite
import com.verdenroz.vpnchain.core.model.OutsideExit
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.UserSettings
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.bareHost
import com.verdenroz.vpnchain.core.model.offersDirect
import com.verdenroz.vpnchain.core.model.outsideExit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SiteAction { UseMyConnection, Undo, Remove }

data class SiteRow(val domain: String, val host: String, val action: SiteAction)

/** Learned sites by where they go, newest first in each section. */
data class SitesUiState(
    val blocked: List<SiteRow> = emptyList(),
    val direct: List<SiteRow> = emptyList(),
    val warp: List<SiteRow> = emptyList(),
    val entry: List<SiteRow> = emptyList(),
) {
    val isEmpty: Boolean get() = blocked.isEmpty() && direct.isEmpty() && warp.isEmpty() && entry.isEmpty()
}

/**
 * A site learned for WARP is listed there only while the tail is rendered; with
 * WARP off or unregistered it goes through the relay, which refuses it.
 */
internal fun sitesUiState(
    sites: List<LearnedSite>,
    settings: UserSettings,
    profile: SavedProfile?,
    warpRegistered: Boolean,
): SitesUiState {
    val domains = sites.mapTo(HashSet()) { it.domain }
    val directOffered = profile.offersDirect(settings)
    val warpActive = warpRegistered && settings.warpMode != WarpMode.Off && profile?.supportsWarpExit == true
    val sections = sites.sortedByDescending { it.learnedAtMillis }.map { site ->
        val exit = site.outsideExit(settings, profile)
        val section = when {
            site.route == LearnedRoute.Warp && warpActive -> Section.Warp
            exit == OutsideExit.EntryHop -> Section.Entry
            exit == OutsideExit.Direct -> Section.Direct
            else -> Section.Blocked
        }
        val action = when {
            site.route == LearnedRoute.Direct -> SiteAction.Undo
            section == Section.Blocked && site.route == LearnedRoute.DirectAvailable && directOffered ->
                SiteAction.UseMyConnection
            else -> SiteAction.Remove
        }
        section to SiteRow(site.domain, bareHost(site.domain, domains), action)
    }.groupBy({ it.first }, { it.second })
    return SitesUiState(
        blocked = sections[Section.Blocked].orEmpty(),
        direct = sections[Section.Direct].orEmpty(),
        warp = sections[Section.Warp].orEmpty(),
        entry = sections[Section.Entry].orEmpty(),
    )
}

private enum class Section { Blocked, Direct, Warp, Entry }

class SitesViewModel(
    private val learnedSites: LearnedSitesRepository,
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    warpRepository: WarpRepository,
) : ViewModel() {

    val uiState: StateFlow<SitesUiState> =
        combine(
            learnedSites.sites,
            settingsRepository.settings,
            profileRepository.profile,
            warpRepository.registered,
            ::sitesUiState,
        )
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SitesUiState(),
            )

    fun act(row: SiteRow) = viewModelScope.launch {
        when (row.action) {
            SiteAction.UseMyConnection -> learnedSites.allowDirect(row.domain)
            SiteAction.Undo -> learnedSites.forget(row.domain)
            SiteAction.Remove -> learnedSites.remove(row.domain)
        }
    }
}
