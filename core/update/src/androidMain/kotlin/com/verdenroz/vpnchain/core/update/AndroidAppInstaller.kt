package com.verdenroz.vpnchain.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path

/** Installs through a [PackageInstaller] session: the system asks the user and reports back to [InstallStatusReceiver]. */
class AndroidAppInstaller(private val context: Context) : AppInstaller {
    // The ABIs the release splits cover.
    override val artifactKey: String? =
        Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }?.let { "android-$it" }

    override suspend fun install(file: Path, release: AppRelease): InstallResult {
        if (!canInstall()) return InstallResult.NeedsPermission
        val installer = context.packageManager.packageInstaller
        val apk = file.toFile()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(apk.length()) }
        val id = installer.createSession(params)
        val verdict = InstallSessions.expect(id)
        return try {
            withContext(Dispatchers.IO) { commit(installer, id, apk) }
            verdict.await()
        } catch (e: IOException) {
            installer.abandonSession(id)
            InstallResult.Failed(e.message ?: "Could not write the update")
        } finally {
            InstallSessions.forget(id)
        }
    }

    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    override fun allowInstalls() {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun commit(installer: PackageInstaller, id: Int, apk: java.io.File) {
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("vpn-chain.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // The system adds its status extras to this intent, so it has to be mutable.
            val status = Intent(context, InstallStatusReceiver::class.java)
            val sender = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(sender.intentSender)
        }
    }
}
