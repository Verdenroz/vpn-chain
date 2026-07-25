package com.verdenroz.vpnchain.core.ui

import androidx.compose.runtime.Composable
import com.verdenroz.vpnchain.core.model.UiText
import org.jetbrains.compose.resources.stringResource

/** Resolves a [UiText] to a displayable string, deferring localization to the UI layer. */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Dynamic -> value
    is UiText.Resource -> stringResource(resource, *args.toTypedArray())
}
