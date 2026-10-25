package com.verdenroz.vpnchain.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Every abort is a failure. One that arrives without a prompt was the device refusing the install and says [unprompted].
 * One after a prompt says [aborted]: the user declining and a setting such as Samsung's Auto Blocker refusing the install
 * there report the same status, so the message has to cover both.
 */
internal fun installResult(
    status: Int,
    message: String?,
    prompted: Boolean,
    unprompted: String,
    aborted: String,
): InstallResult =
    when {
        status == PackageInstaller.STATUS_SUCCESS -> InstallResult.Started
        status == PackageInstaller.STATUS_FAILURE_ABORTED && prompted -> InstallResult.Failed(aborted)
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Failed(listOfNotNull(unprompted, message).joinToString(" "))
        else -> InstallResult.Failed(message ?: "Install failed, status $status")
    }

/** Install sessions waiting on the system's verdict, by session id. */
internal object InstallSessions {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()
    private val prompted = ConcurrentHashMap.newKeySet<Int>()

    fun expect(id: Int) = CompletableDeferred<InstallResult>().also { waiting[id] = it }

    fun prompt(id: Int) = prompted.add(id)

    fun prompted(id: Int) = id in prompted

    fun forget(id: Int) {
        waiting.remove(id)
        prompted.remove(id)
    }

    fun finish(id: Int, result: InstallResult) = waiting.remove(id)?.complete(result)
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmation(intent) ?: return
            InstallSessions.prompt(id)
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        InstallSessions.finish(
            id,
            installResult(
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                InstallSessions.prompted(id),
                unprompted = context.getString(R.string.install_unprompted),
                aborted = context.getString(R.string.install_aborted),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
