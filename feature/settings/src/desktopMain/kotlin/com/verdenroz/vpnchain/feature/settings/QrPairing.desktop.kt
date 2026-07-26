package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

private const val QR_SIZE_PX = 512

actual fun renderQrCode(text: String): ImageBitmap? = runCatching {
    // Profiles with an entry hop push the payload to ~450 bytes; without
    // an explicit error-correction hint ZXing defaults to the weakest level
    // (L, ~7%), which a phone camera struggles to decode off a screen.
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints)
    val image = BufferedImage(QR_SIZE_PX, QR_SIZE_PX, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until QR_SIZE_PX) {
        for (x in 0 until QR_SIZE_PX) {
            image.setRGB(x, y, if (matrix[x, y]) BLACK else WHITE)
        }
    }
    image.toComposeImageBitmap()
}.getOrNull()

// No camera on desktop — nothing to launch.
@Composable
actual fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit = { onResult(null) }

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
