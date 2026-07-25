package com.verdenroz.vpnchain.core.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.generated.resources.Res
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_engine_stopped
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_no_config_provided
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_start_failed
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus

/**
 * The single Android VpnService. It runs sing-box in-process via libbox's
 * command server: [CommandServer.startOrReloadService] boots the engine, which
 * calls back into [VpnPlatformInterface.openTun] to establish the TUN. A local
 * [CommandClient] streams the engine's logs to the UI.
 */
class VpnChainService : VpnService(), CommandServerHandler {

    private val platformInterface = VpnPlatformInterface(this)
    private var commandServer: CommandServer? = null
    private var logClient: CommandClient? = null
    private var statusClient: CommandClient? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    private val lockdownPoller = Handler(Looper.getMainLooper())

    /** Set while we are dismantling the tunnel ourselves. */
    @Volatile private var tearingDown = false
    private val lockdownCheck = object : Runnable {
        override fun run() {
            refreshLockdownState()
            lockdownPoller.postDelayed(this, LOCKDOWN_POLL_MS)
        }
    }

    internal fun retainTunDescriptor(pfd: ParcelFileDescriptor) {
        tunDescriptor = pfd
    }

    /**
     * `isLockdownEnabled()` (API 29+) reflects the OS setting live, so this is
     * re-checked on a timer rather than once — the user can flip "Block
     * connections without VPN" at any point while we're already running, not
     * just before we start.
     */
    private fun refreshLockdownState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TunnelBridge.setAlwaysOnLockdown(isLockdownEnabled())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Below API 29 there's no isLockdownEnabled() to poll, so this null-intent
        // heuristic is the best available signal there (see TunnelBridge).
        if (intent == null) TunnelBridge.markAlwaysOnDetected(applicationContext)
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (worker != null) return
        val config = TunnelBridge.pendingConfig
        if (config.isNullOrBlank()) {
            TunnelBridge.setState(TunnelState.Error, UiText.Resource(Res.string.tunnel_error_no_config_provided))
            stopSelf()
            return
        }

        tearingDown = false
        startForegroundNotification()
        TunnelBridge.setState(TunnelState.Connecting)
        refreshLockdownState()
        lockdownPoller.removeCallbacks(lockdownCheck)
        lockdownPoller.postDelayed(lockdownCheck, LOCKDOWN_POLL_MS)

        worker = Thread {
            try {
                ensureSetup()
                val server = CommandServer(this, platformInterface)
                server.start()
                commandServer = server
                server.startOrReloadService(config, OverrideOptions())
                startLogClient()
                startStatusClient()
                TunnelBridge.setState(TunnelState.Connected)
                updateNotification(getString(R.string.tunnel_notification_connected))
            } catch (t: Throwable) {
                TunnelBridge.log("start failed: ${t.message}")
                TunnelBridge.setState(
                    TunnelState.Error,
                    t.message?.let(UiText::Dynamic) ?: UiText.Resource(Res.string.tunnel_error_start_failed),
                )
                cleanup()
                stopSelf()
            }
        }.also { it.start() }
    }

    /**
     * Reports an unexpected stop as [TunnelState.Error] rather than
     * Disconnected: that is what tells ChainSupervisor to reconnect, where a
     * clean Disconnected would read as the user having asked for it.
     */
    private fun onEngineDied() {
        if (TunnelBridge.status.value.state != TunnelState.Connected) return
        TunnelBridge.setState(
            TunnelState.Error,
            UiText.Resource(Res.string.tunnel_error_engine_stopped),
        )
        cleanup()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopTunnel() {
        TunnelBridge.setState(TunnelState.Disconnected)
        cleanup()
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanup() {
        tearingDown = true
        lockdownPoller.removeCallbacks(lockdownCheck)
        runCatching { logClient?.disconnect() }
        logClient = null
        runCatching { statusClient?.disconnect() }
        statusClient = null
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        worker = null
    }

    private fun ensureSetup() {
        if (didSetup) return
        val work = filesDir.resolve("sing-box").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = work.absolutePath
                tempPath = cacheDir.absolutePath
                fixAndroidStack = false
            },
        )
        didSetup = true
    }

    private fun startLogClient() {
        val client = CommandClient(
            LogHandler(),
            CommandClientOptions().apply { addCommand(Libbox.CommandLog) },
        )
        runCatching { client.connect() }
            .onSuccess { logClient = client }
            .onFailure { TunnelBridge.log("log stream unavailable: ${it.message}") }
    }

    private fun startStatusClient() {
        val client = CommandClient(
            StatusHandler(),
            CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = STATUS_INTERVAL_NS
            },
        )
        runCatching { client.connect() }
            .onSuccess { statusClient = client }
            .onFailure { TunnelBridge.log("status stream unavailable: ${it.message}") }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    // --- CommandServerHandler: engine → app ---

    override fun serviceReload() = Unit

    override fun serviceStop() {
        stopTunnel()
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply { available = false; enabled = false }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun writeDebugMessage(message: String) {
        TunnelBridge.log(message)
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DEFAULT importance (muted) so the shade doesn't demote it to the
            // silent section; the old LOW channel is dropped because a channel's
            // importance can't be raised in place on existing installs.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tunnel_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val notification = buildNotification(getString(R.string.tunnel_notification_connecting))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Launch intent rather than an Activity class: keeps core:tunnel from
        // depending on the app module that hosts the UI.
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        // Safe from the shade: while this foreground service runs, the app is
        // exempt from background startService restrictions.
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, VpnChainService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val disconnectAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_vpn_chain_logo),
            getString(R.string.tunnel_notification_action_disconnect),
            disconnect,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tunnel_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_chain_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { openApp?.let(::setContentIntent) }
            .addAction(disconnectAction)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** Forwards the engine's periodic traffic counters onto the shared bridge. */
    private inner class StatusHandler : CommandClientHandler {
        override fun writeStatus(message: StatusMessage) {
            if (message.trafficAvailable) {
                TunnelBridge.updateTraffic(
                    message.uplinkTotal,
                    message.downlinkTotal,
                    message.uplink,
                    message.downlink,
                )
            }
        }
        override fun connected() = Unit

        /**
         * The engine going away underneath us. Nothing else on Android notices
         * a post-start death: the worker thread has long since returned, so
         * without this the tunnel reads Connected while carrying no traffic.
         */
        override fun disconnected(message: String?) {
            if (tearingDown || TunnelBridge.status.value.state != TunnelState.Connected) return
            TunnelBridge.log("sing-box status stream ended: ${message ?: "no reason given"}")
            // Hopped to the main thread because this runs on a libbox callback,
            // and cleanup() disconnects the very client calling us.
            lockdownPoller.post { onEngineDied() }
        }
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun clearLogs() = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(message: ConnectionEvents) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
    }

    /** Forwards engine log lines onto the shared bridge for the Logs screen. */
    private inner class LogHandler : CommandClientHandler {
        override fun writeLogs(messageList: LogIterator) {
            while (messageList.hasNext()) TunnelBridge.log(messageList.next().message)
        }
        override fun connected() = Unit
        override fun disconnected(message: String?) = Unit
        override fun clearLogs() = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(message: ConnectionEvents) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
    }

    companion object {
        const val ACTION_START = "com.verdenroz.vpnchain.action.START"
        const val ACTION_STOP = "com.verdenroz.vpnchain.action.STOP"
        // Shared with androidApp's ChainNotificationUpdater, which enriches the
        // connected notification with route data the service can't reach.
        const val CHANNEL_ID = "vpn-chain-status"
        const val NOTIFICATION_ID = 1
        private const val LEGACY_CHANNEL_ID = "vpn-chain"
        private const val LOCKDOWN_POLL_MS = 3_000L
        private const val STATUS_INTERVAL_NS = 1_000_000_000L

        @Volatile
        private var didSetup = false
    }
}
