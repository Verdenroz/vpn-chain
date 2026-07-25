package com.verdenroz.vpnchain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.verdenroz.vpnchain.app.VpnChainApp
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.tunnel.VpnPermissionCoordinator
import com.verdenroz.vpnchain.feature.settings.QrScanRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val connectChain: ConnectChainUseCase by inject()

    private var pendingConsent: CompletableDeferred<Boolean>? = null

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingConsent?.complete(result.resultCode == Activity.RESULT_OK)
            pendingConsent = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val consentRequester: suspend () -> Boolean = {
        val prepareIntent: Intent? = VpnService.prepare(this)
        if (prepareIntent == null) {
            true
        } else {
            val deferred = CompletableDeferred<Boolean>()
            pendingConsent = deferred
            vpnConsentLauncher.launch(prepareIntent)
            deferred.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VpnPermissionCoordinator.bind(consentRequester)
        ensureNotificationPermission()
        setContent {
            VpnChainApp()
        }
        handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
    }

    /** Entry point for the QS tile and widgets, which can't run consent or navigation themselves. */
    private fun handleExternalIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_CONNECT -> lifecycleScope.launch { connectChain() }
            ACTION_SCAN_QR -> QrScanRequest.request()
        }
    }

    /**
     * Android 13+ hides the foreground-service notification (and its Disconnect
     * action) unless this runtime permission is granted, so ask up front.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        VpnPermissionCoordinator.unbind(consentRequester)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.verdenroz.vpnchain.action.CONNECT"
        const val ACTION_SCAN_QR = "com.verdenroz.vpnchain.action.SCAN_QR"
    }
}
