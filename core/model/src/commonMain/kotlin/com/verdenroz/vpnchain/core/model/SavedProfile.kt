package com.verdenroz.vpnchain.core.model

import kotlinx.serialization.Serializable

/**
 * A named [ChainProfile] the user can switch to.
 *
 * The id is stable and separate from the name so renaming a profile — or
 * pointing it at a different relay — doesn't orphan the active selection.
 */
@Serializable
data class SavedProfile(
    val id: String,
    val name: String,
    val profile: ChainProfile,
)
