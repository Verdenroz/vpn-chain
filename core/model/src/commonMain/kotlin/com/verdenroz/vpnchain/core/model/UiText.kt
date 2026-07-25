package com.verdenroz.vpnchain.core.model

import org.jetbrains.compose.resources.StringResource

/**
 * A string that a non-Composable layer (domain/data/tunnel) can produce without
 * resolving it — keeps localization at the UI layer, which is the only place
 * with a [org.jetbrains.compose.resources.stringResource] context.
 */
sealed interface UiText {
    data class Dynamic(val value: String) : UiText
    data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText
}
