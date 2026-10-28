package com.verdenroz.vpnchain.feature.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** One pixel per module; the caller scales by a whole number of device pixels. */
internal fun qrMatrix(text: String): BitMatrix {
    // Entry hop payloads pass 350 bytes; ZXing's default level L is too weak for a camera reading a screen.
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    // A size below the module count makes ZXing emit one pixel per module, quiet zone included.
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
}
