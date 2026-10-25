package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.data.LearnedSitesRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.data.SettingsRepository
import com.verdenroz.vpnchain.core.model.OutsideChainSite
import com.verdenroz.vpnchain.core.model.outsideExit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The sites leaving outside the full chain right now, and which way each
 * leaves. Empty unless failing open is on and has found something.
 */
class ObserveOutsideChainSitesUseCase(
    private val learnedSites: LearnedSitesRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
) {
    operator fun invoke(): Flow<List<OutsideChainSite>> =
        combine(
            learnedSites.sites,
            settingsRepository.settings,
            profileRepository.profile,
        ) { sites, settings, profile ->
            sites.mapNotNull { site ->
                site.outsideExit(settings, profile)?.let { OutsideChainSite(site.domain, it) }
            }
        }
}
