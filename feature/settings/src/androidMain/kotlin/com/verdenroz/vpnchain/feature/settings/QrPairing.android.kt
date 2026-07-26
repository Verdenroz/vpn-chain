package com.verdenroz.vpnchain.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.verdenroz.vpnchain.core.designsystem.component.PanelButton
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_scan_cancel
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_scan_hint
import com.verdenroz.vpnchain.feature.settings.generated.resources.settings_scan_no_camera_permission
import org.jetbrains.compose.resources.stringResource

// No display surface worth rendering to on a phone — Android only scans.
actual fun renderQrCode(text: String): ImageBitmap? = null

private enum class ScanState { Idle, Scanning, PermissionDenied }

@Composable
actual fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ScanState.Idle) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        state = if (granted) ScanState.Scanning else ScanState.PermissionDenied
    }

    when (state) {
        ScanState.Idle -> Unit
        ScanState.Scanning -> ScanOverlay(
            onDismiss = { state = ScanState.Idle; onResult(null) },
            onDecoded = { state = ScanState.Idle; onResult(it) },
        )
        ScanState.PermissionDenied -> MessageOverlay(
            message = stringResource(Res.string.settings_scan_no_camera_permission),
            onDismiss = { state = ScanState.Idle; onResult(null) },
        )
    }

    return remember(requestPermission) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) state = ScanState.Scanning else requestPermission.launch(Manifest.permission.CAMERA)
        }
    }
}

@Composable
private fun ScanOverlay(onDismiss: () -> Unit, onDecoded: (String) -> Unit) {
    FullScreenOverlay(onDismiss) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            QrScanCamera(onDecoded = onDecoded, modifier = Modifier.fillMaxSize())
        }
        OverlayText(stringResource(Res.string.settings_scan_hint))
    }
}

@Composable
private fun MessageOverlay(message: String, onDismiss: () -> Unit) {
    FullScreenOverlay(onDismiss) { OverlayText(message) }
}

@Composable
private fun FullScreenOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
            PanelButton(text = stringResource(Res.string.settings_scan_cancel), onClick = onDismiss)
        }
    }
}

@Composable
private fun OverlayText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        textAlign = TextAlign.Center,
    )
}
