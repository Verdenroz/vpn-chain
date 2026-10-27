package com.verdenroz.vpnchain.feature.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes at one module per cell. The caller scales up by a whole number of
 * device pixels; baking a fixed pixel size in here instead leaves modules on
 * fractional pixels once Compose fits the bitmap to its layout size, and the
 * ragged module widths that produces are what a phone camera fails to lock onto.
 */
internal fun qrMatrix(text: String): BitMatrix {
    // Profiles with an entry hop push the payload past 350 bytes; without
    // an explicit error-correction hint ZXing defaults to the weakest level
    // (L, ~7%), which a phone camera struggles to decode off a screen.
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    // A requested size below the natural module count makes ZXing fall back to
    // exactly one pixel per module, quiet zone included.
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
}
