package com.verdenroz.vpnchain.feature.settings

import java.io.File

actual fun readDefaultSecretsEnv(): String? {
    val file = File(System.getProperty("user.home"), ".config/vpn-chain/secrets.env")
    return file.takeIf { it.isFile }?.readText()
}
