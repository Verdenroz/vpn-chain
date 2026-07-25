package com.verdenroz.vpnchain.core.common

/** The running app shell — gates platform-only features/text (CLI paths, root/sudo, camera, ...). */
enum class Platform { Android, Desktop }

expect val currentPlatform: Platform
