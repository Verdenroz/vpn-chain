package com.verdenroz.vpnchain

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.verdenroz.vpnchain.app.VpnChainApp
import com.verdenroz.vpnchain.core.tunnel.VpnPermissionCoordinator
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var pendingConsent: CompletableDeferred<Boolean>? = null

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingConsent?.complete(result.resultCode == Activity.RESULT_OK)
            pendingConsent = null
        }

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
        setContent {
            VpnChainApp()
        }
    }

    override fun onDestroy() {
        VpnPermissionCoordinator.unbind(consentRequester)
        super.onDestroy()
    }
}
