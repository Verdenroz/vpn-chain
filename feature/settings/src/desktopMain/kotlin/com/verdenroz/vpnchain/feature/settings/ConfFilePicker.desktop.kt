package com.verdenroz.vpnchain.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import com.verdenroz.vpnchain.feature.settings.generated.resources.Res
import com.verdenroz.vpnchain.feature.settings.generated.resources.hops_choose_file
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun rememberConfFilePicker(onPicked: (PickedConf?) -> Unit): () -> Unit {
    val title = stringResource(Res.string.hops_choose_file)
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val open = remember { AtomicBoolean(false) }
    return remember(title) {
        {
            if (open.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        latest(withContext(Dispatchers.IO) { chooseConf(title) })
                    } finally {
                        open.set(false)
                    }
                }
            }
            Unit
        }
    }
}

private fun chooseConf(title: String): PickedConf? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        directory = System.getProperty("user.home")
        file = "*.conf"
        setFilenameFilter { _, name -> name.endsWith(".conf") }
        isVisible = true
    }
    val name = dialog.file ?: return null
    return readPickedConf { File(dialog.directory, name).inputStream() }
}
