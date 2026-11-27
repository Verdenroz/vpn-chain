package com.verdenroz.vpnchain.core.domain

import com.verdenroz.vpnchain.core.config.WireGuardConfParser
import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlinx.coroutines.flow.first

enum class Hop { Entry, Exit }

/** The pasted config's private key is the one [other] already uses. */
class SameKeyAsOtherHop(val other: Hop) : IllegalArgumentException("Same private key as the ${other.name} hop")

/**
 * Sets or clears the active connection's entry hop and this device's exit. A
 * pasted or chosen conf names a server the user may not have meant to trust, so
 * review parses it without saving; the set calls are for after they confirmed,
 * and restart a wanted tunnel onto the change.
 */
class ChangeHopsUseCase(
    private val profiles: ProfileRepository,
    private val chain: ChainRepository,
) {
    /** A failed [reconnect] leaves the change saved, so it must not read as a failed save. */
    data class Saved(val reconnect: Result<Unit>)

    suspend fun reviewEntryHop(conf: String): Result<WireGuardEntry> = review(conf, other = Hop.Exit)

    suspend fun reviewDeviceExit(conf: String): Result<WireGuardEntry> = review(conf, other = Hop.Entry)

    suspend fun setEntryHop(entry: WireGuardEntry?): Result<Saved> =
        profiles.setEntryHop(entry).map { Saved(chain.restartIfWanted()) }

    suspend fun setDeviceExit(exit: WireGuardEntry?): Result<Saved> =
        profiles.setDeviceExit(exit).map { Saved(chain.restartIfWanted()) }

    private suspend fun review(conf: String, other: Hop): Result<WireGuardEntry> {
        val parsed = WireGuardConfParser.parse(conf)
        val entry = parsed.getOrNull() ?: return parsed
        val active = profiles.profile.first()
        val otherKey = when (other) {
            Hop.Entry -> active?.wireGuard?.privateKey
            Hop.Exit -> active?.deviceExit?.privateKey
        }
        return if (otherKey == entry.privateKey) Result.failure(SameKeyAsOtherHop(other)) else parsed
    }
}
