package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

actual fun renderQrCode(text: String): ImageBitmap? = runCatching {
    val matrix = qrMatrix(text)
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            image.setRGB(x, y, if (matrix[x, y]) BLACK else WHITE)
        }
    }
    image.toComposeImageBitmap()
}.getOrNull()

// No camera on desktop — nothing to launch.
@Composable
actual fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit = { onResult(null) }

@Composable
actual fun rememberTextSharer(): ((String) -> Unit)? = null

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
