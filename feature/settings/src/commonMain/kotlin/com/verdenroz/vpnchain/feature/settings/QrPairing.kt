package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Renders [text] (a secrets.env payload) as a QR code bitmap, or null if unsupported. */
expect fun renderQrCode(text: String): ImageBitmap?

/**
 * Returns a launcher that opens the platform's QR scanner and calls [onResult] with the
 * decoded text, or null if cancelled/failed. No-op on platforms without scanning.
 */
@Composable
expect fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit
