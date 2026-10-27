package com.verdenroz.vpnchain.core.update.di

import com.verdenroz.vpnchain.core.common.appVersion
import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.common.di.ioDispatcherQualifier
import com.verdenroz.vpnchain.core.update.AppInstaller
import com.verdenroz.vpnchain.core.update.AppUpdates
import com.verdenroz.vpnchain.core.update.DesktopAppInstaller
import com.verdenroz.vpnchain.core.update.PackageInstall
import com.verdenroz.vpnchain.core.update.ReleaseRepository
import com.verdenroz.vpnchain.core.update.createReleaseTransport
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koin.core.module.Module
import org.koin.dsl.module

private fun xdgDir(variable: String, fallback: String): Path {
    val base = System.getenv(variable)?.takeIf { it.isNotEmpty() } ?: "${System.getProperty("user.home")}/$fallback"
    return Path.of(base, "vpn-chain")
}

actual val updateModule: Module = module {
    single { ReleaseRepository(createReleaseTransport(), FileSystem.SYSTEM, get(ioDispatcherQualifier)) }
    single<AppInstaller> { DesktopAppInstaller(PackageInstall()) }
    single(createdAtStart = true) {
        AppUpdates(
            repository = get(),
            installer = get(),
            directory = xdgDir("XDG_CACHE_HOME", ".cache").resolve("update").toOkioPath(),
            installedVersion = appVersion,
        ).also { updates ->
            get<CoroutineScope>(applicationScopeQualifier).launch { updates.run() }
        }
    }
}
