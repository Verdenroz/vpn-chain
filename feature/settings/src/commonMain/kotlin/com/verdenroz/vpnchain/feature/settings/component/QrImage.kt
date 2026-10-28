package com.verdenroz.vpnchain.feature.settings.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import com.verdenroz.vpnchain.feature.settings.QR_MIN_MODULE_PX
import com.verdenroz.vpnchain.feature.settings.QR_TARGET_SIZE
import com.verdenroz.vpnchain.feature.settings.renderQrCode
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { renderQrCode(text) } ?: return
    // A fractional scale makes modules uneven in width, which phone cameras fail to lock onto.
    val density = LocalDensity.current
    val modulePx = with(density) { QR_TARGET_SIZE.toPx() } / bitmap.width
    val scale = max(QR_MIN_MODULE_PX, modulePx.roundToInt())
    Image(
        bitmap = bitmap,
        contentDescription = null,
        // Bilinear filtering would blur the square modules.
        filterQuality = FilterQuality.None,
        modifier = modifier.size(with(density) { (bitmap.width * scale).toDp() }),
    )
}
