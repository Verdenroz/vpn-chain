package com.verdenroz.vpnchain.feature.settings

import com.verdenroz.vpnchain.core.common.PrivateStorage
import java.io.File

actual fun readDefaultSecretsEnv(): String? {
    val file = File(PrivateStorage.configDir, "secrets.env")
    return file.takeIf { it.isFile }?.readText()
}
