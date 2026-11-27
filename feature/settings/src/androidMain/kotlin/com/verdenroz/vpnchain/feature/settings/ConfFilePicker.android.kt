package com.verdenroz.vpnchain.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberConfFilePicker(onPicked: (PickedConf?) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onPicked(null)
        } else {
            scope.launch {
                onPicked(withContext(Dispatchers.IO) { readPickedConf { resolver.openInputStream(uri) } })
            }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
