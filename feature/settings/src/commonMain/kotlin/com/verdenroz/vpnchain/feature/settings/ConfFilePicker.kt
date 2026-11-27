package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_file_too_large
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_file_unreadable

/** What choosing a file produced. */
sealed interface PickedConf {
    /** [toString] hides [text], which holds the provider's private key. */
    data class Text(val text: String) : PickedConf {
        override fun toString() = "PickedConf.Text"
    }

    data object Unreadable : PickedConf

    data object TooLarge : PickedConf
}

/**
 * Returns a launcher that asks the user for a WireGuard `.conf` and calls
 * [onPicked] with what it held, or null when they cancelled.
 */
@Composable
expect fun rememberConfFilePicker(onPicked: (PickedConf?) -> Unit): () -> Unit

internal fun PickedConf.problem(): UiText? = when (this) {
    is PickedConf.Text -> null
    PickedConf.Unreadable -> UiText.Resource(Res.string.hops_file_unreadable)
    PickedConf.TooLarge -> UiText.Resource(Res.string.hops_file_too_large)
}
