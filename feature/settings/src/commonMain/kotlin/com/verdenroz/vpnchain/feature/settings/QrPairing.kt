package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp

/**
 * Size the QR aims for on screen. The drawn size is rounded off this to a whole
 * number of pixels per module, so it varies a little with the payload's density.
 */
internal val QR_TARGET_SIZE = 320.dp

/** Below roughly this, a phone camera can no longer resolve individual modules. */
internal const val QR_MIN_MODULE_PX = 3

/**
 * Renders [text] (a secrets.env payload) as a QR code bitmap at one pixel per
 * module, or null if unsupported. Display it scaled by a whole number of device
 * pixels — see [QR_TARGET_SIZE].
 */
expect fun renderQrCode(text: String): ImageBitmap?

/**
 * Returns a launcher that opens the platform's QR scanner and calls [onResult] with the
 * decoded text, or null if cancelled/failed. No-op on platforms without scanning.
 */
@Composable
expect fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit
