package com.verdenroz.vpnchain.core.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.generated.resources.Res
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_engine_stopped
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_no_config_provided
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_no_traffic
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_start_failed
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_stopped_carrying
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single Android VpnService. It runs sing-box in-process via libbox's
 * command server: [CommandServer.startOrReloadService] boots the engine, which
 * calls back into [VpnPlatformInterface.openTun] to establish the TUN. A local
 * [CommandClient] streams the engine's logs to the UI.
 *
 * The service's lifetime is the *user's* connection intent, not the engine's.
 * A drop tears down the engine and keeps the service in the foreground, because
 * a service that stops itself here leaves the process cached — where Doze kills
 * it, taking the reconnect loop with it — and Android 12+ then refuses the
 * background foreground-service start that would bring it back. Only an explicit
 * stop or an OS revoke ends it.
 */
class VpnChainService : VpnService(), CommandServerHandler {

    private val platformInterface = VpnPlatformInterface(this)
    private var commandServer: CommandServer? = null
    private var logClient: CommandClient? = null
    private var statusClient: CommandClient? = null
    private var tunDescriptor: ParcelFileDescriptor? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Mutex()
    private val healthLock = Mutex()
    private var healthJob: Job? = null

    /** Debounces triggers that arrive together; see [verifyCarrying]. */
    @Volatile private var lastVerifiedAt = 0L

    /**
     * Which start attempt owns the status. A boot that is still unwinding when
     * the next one begins must not report the state of an engine that is gone.
     */
    private val generation = AtomicInteger(0)

    private val networkMonitor by lazy { AndroidNetworkMonitor(applicationContext) }
    private val wakeGuard by lazy { AndroidWakeGuard(applicationContext) }

    /**
     * Kept hot rather than sampled per check: a fresh collection has to wait for
     * the system's first callback, and reading it cold would answer "offline"
     * for every health check — which is the one answer that suppresses them all.
     */
    private val online by lazy {
        networkMonitor.online.stateIn(serviceScope, SharingStarted.Eagerly, initialValue = true)
    }

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
            // Distinct from ACTION_STOP: this is a stop the repository never saw,
            // so nothing else will clear the user's connection intent for it.
            ACTION_STOP_BY_USER -> {
                TunnelBridge.signalUserStop()
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        // Synchronous, and before anything that can suspend: a service started
        // with startForegroundService has about five seconds to post this.
        startForegroundNotification()
        val gen = generation.incrementAndGet()
        tearingDown = false
        TunnelBridge.setState(TunnelState.Connecting)
        refreshLockdownState()
        lockdownPoller.removeCallbacks(lockdownCheck)
        lockdownPoller.postDelayed(lockdownCheck, LOCKDOWN_POLL_MS)

        serviceScope.launch {
            wakeGuard.awake {
                val booted = lifecycleLock.withLock {
                    if (commandServer != null) return@withLock true
                    bootEngine(gen)
                }
                // Outside the lock: confirming traffic takes tens of seconds, and
                // a Disconnect arriving meanwhile has to be able to tear it down.
                if (booted) confirmCarrying(gen)
            }
        }
    }

    /** @return whether the engine is running and streaming. Call under [lifecycleLock]. */
    private suspend fun bootEngine(gen: Int): Boolean {
        val config = resolveConfig()
        if (config.isNullOrBlank()) {
            dropEngineLocked(gen, UiText.Resource(Res.string.tunnel_error_no_config_provided))
            return false
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                ensureSetup()
                val server = CommandServer(this@VpnChainService, platformInterface)
                server.start()
                commandServer = server
                server.startOrReloadService(config, OverrideOptions())
                startLogClient()
                startStatusClient()
            }
            // A stop that landed while the engine was booting cannot reach in and
            // close a server that did not exist yet, so the boot has to check.
            if (!owns(gen)) {
                cleanup()
                false
            } else {
                true
            }
        }.getOrElse { t ->
            TunnelBridge.log("start failed: ${t.message}")
            dropEngineLocked(
                gen,
                t.message?.let(UiText::Dynamic) ?: UiText.Resource(Res.string.tunnel_error_start_failed),
            )
            false
        }
    }

    /**
     * A sticky restart or an Always-on start hands us a null intent and an empty
     * bridge: the process that held the rendered config is gone. Rendering a
     * fresh one from the stored profile is what makes an OS-driven start work at
     * all — without it those starts died on "no configuration provided".
     */
    private suspend fun resolveConfig(): String? =
        TunnelBridge.pendingConfig ?: TunnelBridge.renderConfig()

    /**
     * Connected has to mean the chain carries traffic. The TUN exists within
     * milliseconds — long before the entry hop has handshaked — and reporting
     * that as up hands the user a tunnel that swallows every request.
     */
    private suspend fun confirmCarrying(gen: Int) {
        TunnelBridge.log("tunnel up, waiting for the chain to carry traffic…")
        repeat(READINESS_ATTEMPTS) {
            if (!owns(gen) || TunnelBridge.status.value.state != TunnelState.Connecting) return

            if (ChainProbe.carriesTraffic(READINESS_PROBE_TIMEOUT_MS)) {
                if (!owns(gen)) return
                TunnelBridge.setState(TunnelState.Connected)
                updateNotification(getString(R.string.tunnel_notification_connected))
                startHealthWatch(gen)
                return
            }
            delay(READINESS_RETRY_MS)
        }
        if (!owns(gen) || TunnelBridge.status.value.state != TunnelState.Connecting) return
        dropEngine(gen, UiText.Resource(Res.string.tunnel_error_no_traffic))
    }

    /**
     * Watches a live session for the failure nothing else can see: a chain that
     * came up carrying traffic and later went silent. The engine stays alive and
     * the TUN stays present through that, which is why it takes a manual
     * reconnect to clear today.
     */
    private fun startHealthWatch(gen: Int) {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            launch { networkMonitor.linkChanges.collect { verifyCarrying(gen) } }
            launch { screenWakes().collect { verifyCarrying(gen) } }
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                verifyCarrying(gen)
            }
        }
    }

    /** `ACTION_SCREEN_ON` is not deliverable to a manifest receiver, only a live one. */
    private fun screenWakes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        awaitClose { runCatching { unregisterReceiver(receiver) } }
    }

    private suspend fun verifyCarrying(gen: Int) {
        healthLock.withLock {
            if (!owns(gen) || TunnelBridge.status.value.state != TunnelState.Connected) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastVerifiedAt < HEALTH_MIN_GAP_MS) return
            if (!online.value) return

            val carries = wakeGuard.awake { ChainProbe.carriesTraffic() }
            lastVerifiedAt = SystemClock.elapsedRealtime()
            if (carries) return
            if (!owns(gen) || TunnelBridge.status.value.state != TunnelState.Connected) return
            TunnelBridge.log("chain stopped carrying traffic — tearing it down so it can be rebuilt")
            dropEngine(gen, UiText.Resource(Res.string.tunnel_error_stopped_carrying))
        }
    }

    /**
     * Reports a drop as [TunnelState.Error] rather than Disconnected: that is
     * what tells ChainSupervisor to reconnect, where a clean Disconnected would
     * read as the user having asked for it. The foreground notification stays —
     * see the class comment for why the service must not stop itself here.
     */
    private suspend fun dropEngine(gen: Int, detail: UiText) =
        lifecycleLock.withLock { dropEngineLocked(gen, detail) }

    /** [dropEngine] for callers already holding [lifecycleLock] — it is not reentrant. */
    private fun dropEngineLocked(gen: Int, detail: UiText) {
        if (!owns(gen)) return
        cleanup()
        TunnelBridge.setState(TunnelState.Error, detail)
        updateNotification(getString(R.string.tunnel_notification_reconnecting))
    }

    private fun onEngineDied() {
        if (TunnelBridge.status.value.state != TunnelState.Connected) return
        val gen = generation.get()
        serviceScope.launch { dropEngine(gen, UiText.Resource(Res.string.tunnel_error_engine_stopped)) }
    }

    private fun owns(gen: Int): Boolean = generation.get() == gen

    private fun stopTunnel() {
        generation.incrementAndGet()
        TunnelBridge.setState(TunnelState.Disconnected)
        cleanup()
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanup() {
        tearingDown = true
        healthJob?.cancel()
        healthJob = null
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
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * The OS pulled our consent — the user disconnected us from Settings, or
     * another VPN app took over. Either way they did not ask to come back, so
     * this counts as a user stop rather than a drop to reconnect from.
     */
    override fun onRevoke() {
        TunnelBridge.signalUserStop()
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
            Intent(this, VpnChainService::class.java).setAction(ACTION_STOP_BY_USER),
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
         * a post-start death: the boot coroutine has long since returned, so
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

        /** Stop originating from a notification key, outside the app's own path. */
        const val ACTION_STOP_BY_USER = "com.verdenroz.vpnchain.action.STOP_BY_USER"
        // Shared with androidApp's ChainNotificationUpdater, which enriches the
        // connected notification with route data the service can't reach.
        const val CHANNEL_ID = "vpn-chain-status"
        const val NOTIFICATION_ID = 1
        private const val LEGACY_CHANNEL_ID = "vpn-chain"
        private const val LOCKDOWN_POLL_MS = 3_000L
        private const val STATUS_INTERVAL_NS = 1_000_000_000L
        private const val READINESS_ATTEMPTS = 5
        private const val READINESS_RETRY_MS = 1_500L
        private const val READINESS_PROBE_TIMEOUT_MS = 3_000
        private const val HEALTH_INTERVAL_MS = 45_000L
        private const val HEALTH_MIN_GAP_MS = 10_000L

        @Volatile
        private var didSetup = false
    }
}
