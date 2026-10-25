package com.verdenroz.vpnchain.core.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Open only while the screen is on and the device is out of Doze. */
internal class AndroidProbeGate(context: Context) : ProbeGate {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)

    override val open: Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(awake())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        appContext.registerReceiver(receiver, filter)
        trySend(awake())
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private fun awake() = power.isInteractive && !power.isDeviceIdleMode
}
