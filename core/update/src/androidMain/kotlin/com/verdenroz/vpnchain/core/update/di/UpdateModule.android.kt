package com.verdenroz.vpnchain.core.update.di

import com.verdenroz.vpnchain.core.common.di.applicationScopeQualifier
import com.verdenroz.vpnchain.core.common.di.ioDispatcherQualifier
import com.verdenroz.vpnchain.core.update.AndroidAppInstaller
import com.verdenroz.vpnchain.core.update.AppInstaller
import com.verdenroz.vpnchain.core.update.AppUpdates
import com.verdenroz.vpnchain.core.update.ReleaseRepository
import com.verdenroz.vpnchain.core.update.createReleaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val updateModule: Module = module {
    single { ReleaseRepository(createReleaseTransport(), FileSystem.SYSTEM, get(ioDispatcherQualifier)) }
    single<AppInstaller> { AndroidAppInstaller(androidContext()) }
    single(createdAtStart = true) {
        val context = androidContext()
        AppUpdates(
            repository = get(),
            installer = get(),
            directory = context.cacheDir.absolutePath.toPath() / "update",
            installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
        ).also { updates ->
            get<CoroutineScope>(applicationScopeQualifier).launch { updates.run() }
        }
    }
}
