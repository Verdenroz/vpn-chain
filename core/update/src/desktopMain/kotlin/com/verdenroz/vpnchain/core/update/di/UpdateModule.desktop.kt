package com.verdenroz.vpnchain.core.update.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.update.AppInstaller
import com.verdenroz.vpnchain.core.update.AppRelease
import com.verdenroz.vpnchain.core.update.AppUpdates
import com.verdenroz.vpnchain.core.update.InstallResult
import com.verdenroz.vpnchain.core.update.ReleaseRepository
import com.verdenroz.vpnchain.core.update.createReleaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

private object NoInstaller : AppInstaller {
    override val artifactKey: String? = null

    override suspend fun install(file: Path, release: AppRelease): InstallResult = InstallResult.Failed("")
}

actual val updateModule: Module = module {
    single { ReleaseRepository(createReleaseTransport(), FileSystem.SYSTEM) }
    single<AppInstaller> { NoInstaller }
    single(createdAtStart = true) {
        AppUpdates(
            repository = get(),
            installer = get(),
            directory = System.getProperty("java.io.tmpdir").toPath() / "vpn-chain-update",
            installedVersion = System.getProperty("vpnchain.version").orEmpty(),
        ).also { updates ->
            get<CoroutineScope>(applicationScopeQualifier).launch { updates.run() }
        }
    }
}
