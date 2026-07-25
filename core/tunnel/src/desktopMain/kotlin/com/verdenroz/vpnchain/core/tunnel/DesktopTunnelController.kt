package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.common.currentTimeMillis
import com.verdenroz.vpnchain.core.config.ClashApi
import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.KillSwitchState
import com.verdenroz.vpnchain.core.model.TunnelState
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.generated.resources.Res
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_already_running_hint
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_cannot_launch
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_exit_generic
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_exit_kill_switch_engaged
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_kill_switch_unavailable
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_relay_already_running_pid
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_error_tun_privilege_hint
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_adopted_relay_port
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_adopted_system_wide
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_already_state
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_kill_switch_disengaged
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_kill_switch_engaged
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_kill_switch_helper_error
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_log_kill_switch_no_exempt
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_status_sharing_cli
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_status_tun
import com.verdenroz.vpnchain.core.tunnel.generated.resources.tunnel_status_tun_adopted
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Runs the relay via the `sing-box` binary, coexisting with `cli/vpn-chain`.
 *
 * The relay is a singleton: one sing-box on `127.0.0.1:<proxyPort>`, tracked by
 * a shared pidfile under `$XDG_RUNTIME_DIR/vpn-chain/relay.pid` (same location
 * and format the CLI uses). So this controller:
 *
 * - **adopts** a relay that's already up (started by the CLI or a prior GUI run)
 *   instead of starting a competing one that would fail to bind the port;
 * - writes the shared pidfile when it starts its own, so `vpn-chain status`/`down`
 *   see it too;
 * - polls the port so the UI reflects relay up/down driven from the CLI.
 *
 * The Proton entry hop is managed outside this (Proton app or the CLI's `up`).
 */
class DesktopTunnelController(
    private val scope: CoroutineScope,
    private val singBoxBin: String = "sing-box",
    private val killSwitchBin: String = "vpn-chain-killswitch",
) : TunnelController {

    private val _stats = MutableStateFlow(SessionStats())
    override val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    @Volatile private var clashApi: ClashApi? = null
    private var statsJob: Job? = null

    private val _status = MutableStateFlow(ChainStatus())
    override val status: StateFlow<ChainStatus> = _status.asStateFlow()

    // replay so a collector that subscribes after connect still gets recent history.
    private val _logs = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 256)
    override val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val runtimeDir = File(System.getenv("XDG_RUNTIME_DIR") ?: "/tmp", "vpn-chain")
    private val pidFile = File(runtimeDir, "relay.pid")
    // Records what the running relay actually is, so a restarted GUI adopting it
    // knows the mode and whether a kill switch is engaged. Absent for CLI-started
    // relays, which are always relay-only with no kill switch.
    private val stateFile = File(runtimeDir, "relay.state")
    // Shared log sink: the GUI redirects its own relay here, and the CLI's nohup
    // writes here too, so tailing it shows logs whoever started the relay.
    private val relayLog = File(runtimeDir, "relay.log")

    /** Serializes connect/disconnect against each other and the state poller —
     *  two sing-box instances on one TUN take the machine offline. */
    private val startStopLock = Mutex()

    /** Non-null only when this controller launched the relay itself. */
    private var process: Process? = null
    private var readerJob: Job? = null
    private var logTailJob: Job? = null

    @Volatile
    private var proxyPort: Int = DEFAULT_PROXY_PORT

    /** TUN mode routes the whole system; proxy mode is a localhost SOCKS listener. */
    @Volatile
    private var tunMode: Boolean = false

    @Volatile
    private var tunHasEntry: Boolean = false

    @Volatile
    private var killSwitchEnabled: Boolean = true

    /** True once the nftables kill-switch table has been installed for this session. */
    @Volatile
    private var killSwitchEngaged: Boolean = false

    /**
     * Resolves a log string, never throwing — a resource-loading hiccup (rare,
     * but real: a corrupted/mid-rewrite jar) must never abort a caller that's
     * mid kill-switch or process operation. Worst case, a log line is dropped.
     */
    private suspend fun emitLog(resource: StringResource, vararg args: Any) {
        val text = runCatching { getString(resource, *args) }.getOrNull() ?: return
        _logs.tryEmit(text)
    }

    init {
        scope.launch(Dispatchers.IO) {
            // Adopt before the first poll delay: for the seconds this otherwise
            // spends reporting Disconnected while a tunnel is up, anything that
            // probes the network reads the tunnel's answer and believes it came
            // from an untunnelled machine.
            startStopLock.withLock { adoptRunningSession() }
            pollExternalState()
        }
    }

    override suspend fun start(configJson: String, killSwitchEnabled: Boolean) = withContext(Dispatchers.IO) {
        startStopLock.withLock {
            val current = _status.value.state
            if (current == TunnelState.Connecting || current == TunnelState.Connected) {
                emitLog(Res.string.tunnel_log_already_state, current.name.lowercase())
                return@withLock
            }
            if (process?.isAlive == true) return@withLock
            clashApi = RelayConfig.clashApi(configJson)
        tunMode = RelayConfig.isTun(configJson)
            tunHasEntry = tunMode && RelayConfig.hasWireGuardEntry(configJson)
            proxyPort = RelayConfig.proxyPort(configJson, DEFAULT_PROXY_PORT)
            this@DesktopTunnelController.killSwitchEnabled = killSwitchEnabled
            // A relay left running by the CLI or a previous GUI run is invisible to
            // a fresh process. Launching a second sing-box over the same TUN makes
            // it tear down the live instance's routing on exit, which strands the
            // machine offline — so adopt what's running instead of competing.
            if (adoptRunningSession()) return@withLock
            _status.value = ChainStatus(state = TunnelState.Connecting)
            if (tunMode) startTun(configJson) else startProxy(configJson)
        }
    }

    private suspend fun startProxy(configJson: String) {
        val proc = spawnSingBox(configJson) ?: return
        // Readiness isn't a log line (the "started" message is buried), so watch
        // for the proxy port to come up. If the process dies the reader reports it.
        awaitReady(proc) { isRelayListening(proxyPort) }
    }

    private suspend fun startTun(configJson: String) {
        // TUN owns system routing — exclusive, no port adoption. Needs CAP_NET_ADMIN.
        // TUN is now the default, so check up front rather than let sing-box fail
        // silently into a log line the user has to go find.
        if (!hasNetAdminPrivilege()) {
            _status.value = ChainStatus(
                state = TunnelState.Error,
                detail = UiText.Resource(Res.string.tunnel_error_tun_privilege_hint),
            )
            emitLog(Res.string.tunnel_error_tun_privilege_hint)
            return
        }
        // Re-checked after start()'s adoption pass in case a relay appeared since:
        // engaging the kill switch would replace the live session's table, and a
        // second sing-box on the same TUN rips out its routing when it exits.
        if (detectRunningSession() != null) {
            _status.value = ChainStatus(
                state = TunnelState.Error,
                detail = UiText.Resource(Res.string.tunnel_error_already_running_hint),
            )
            emitLog(Res.string.tunnel_error_already_running_hint)
            return
        }
        // Only the WG-entry-hop path needs this: relay-only relies on the real
        // ProtonVPN app's own kill switch instead (see markConnected).
        if (tunHasEntry && killSwitchEnabled) engageKillSwitch(configJson)
        val proc = spawnSingBox(configJson)
        if (proc == null) {
            // Never actually started - nothing to protect, don't leave traffic blocked.
            disengageKillSwitch()
            return
        }
        awaitReady(proc) { tunInterfaceUp() }
    }

    /**
     * Detects a live relay by process and interface, not just the proxy port —
     * the TUN config has no listener at all, so port probing alone reports a
     * running system-wide tunnel as disconnected.
     */
    private fun detectRunningSession(): RelaySession? {
        val pidAlive = livePidFileProcess() != null
        val tunUp = tunInterfaceUp()
        if (!pidAlive && !tunUp) {
            // Port probing is meaningful only for relay-only mode.
            return if (!tunMode && isRelayListening(proxyPort)) {
                unknownSession(tun = false)
            } else {
                null
            }
        }
        return readSessionState() ?: unknownSession(tun = tunUp)
    }

    /** A relay with no state file of ours — the CLI's, which is always relay-only
     *  with no kill switch of its own. */
    private fun unknownSession(tun: Boolean) =
        RelaySession(tun = tun, entry = false, killSwitch = false, killSwitchEnabled = killSwitchEnabled)

    /** Takes ownership of an already-running relay's status instead of starting
     *  a competing one. Returns false when nothing is running. */
    private suspend fun adoptRunningSession(): Boolean {
        val session = detectRunningSession() ?: return false
        tunMode = session.tun
        tunHasEntry = session.entry
        killSwitchEngaged = session.killSwitch
        killSwitchEnabled = session.killSwitchEnabled
        if (session.tun) {
            emitLog(Res.string.tunnel_log_adopted_system_wide)
        } else {
            emitLog(Res.string.tunnel_log_adopted_relay_port, proxyPort)
        }
        markConnected(adopted = true)
        return true
    }

    /**
     * Installs an nftables OUTPUT-chain table that drops all outbound traffic
     * except loopback and the VPS/Proton-endpoint exemptions, so sing-box
     * crashing mid-session blocks traffic instead of leaking it. Doesn't touch
     * the routing table at all, so it can't confuse sing-box's own
     * `auto_detect_interface`. Best-effort: if the helper isn't
     * built/setcapped, or the exempt IPs can't be determined, this warns but
     * does not block connecting.
     */
    private suspend fun engageKillSwitch(configJson: String) {
        val exemptIps = RelayConfig.exemptIps(configJson)
        if (exemptIps.isEmpty()) {
            emitLog(Res.string.tunnel_log_kill_switch_no_exempt)
            return
        }
        val result = runKillSwitchHelper(listOf("up") + exemptIps)
        killSwitchEngaged = result == 0
        if (!killSwitchEngaged) {
            emitLog(Res.string.tunnel_error_kill_switch_unavailable)
        } else {
            emitLog(Res.string.tunnel_log_kill_switch_engaged, exemptIps.joinToString())
        }
    }

    /** [force] tears the table down even when this process didn't install it —
     *  a restarted GUI must still be able to restore networking. `down` is
     *  idempotent, so a no-op call is harmless. */
    private suspend fun disengageKillSwitch(force: Boolean = false) {
        if (!killSwitchEngaged && !force) return
        val wasEngaged = killSwitchEngaged
        runKillSwitchHelper(listOf("down"))
        killSwitchEngaged = false
        if (wasEngaged) emitLog(Res.string.tunnel_log_kill_switch_disengaged)
    }

    private suspend fun runKillSwitchHelper(args: List<String>): Int? {
        var output = ""
        val exit = runCatching {
            val proc = ProcessBuilder(listOf(killSwitchBin) + args).redirectErrorStream(true).start()
            output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching null
            }
            proc.exitValue()
        }.getOrNull() ?: return null
        if (exit != 0) {
            emitLog(
                Res.string.tunnel_log_kill_switch_helper_error,
                "$killSwitchBin ${args.joinToString(" ")}",
                output.trim().ifBlank { "exit $exit" },
            )
        }
        return exit
    }

    /**
     * Best-effort pre-flight check for the `cap_net_admin` capability sing-box
     * needs to create a TUN. Only meaningful on Linux with `getcap` available;
     * anywhere else (root, other OSes, no `getcap`) this defers to sing-box's
     * own error rather than risk a false-positive block.
     */
    private fun hasNetAdminPrivilege(): Boolean {
        if (System.getProperty("user.name") == "root") return true
        if (!System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true)) return true
        val binPath = resolveSingBoxPath() ?: return true
        return runCatching {
            val proc = ProcessBuilder("getcap", binPath).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(2, TimeUnit.SECONDS)
            output.contains("cap_net_admin")
        }.getOrDefault(true)
    }

    private fun resolveSingBoxPath(): String? = runCatching {
        val proc = ProcessBuilder("bash", "-c", "command -v $singBoxBin").start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor(2, TimeUnit.SECONDS)
        out.ifBlank { null }
    }.getOrNull()

    private fun spawnSingBox(configJson: String): Process? {
        // Last line of defence behind start()'s adoption check: never launch over a
        // live relay, and never overwrite its pidfile — that would orphan a process
        // neither the GUI nor the CLI could stop afterwards.
        livePidFileProcess()?.let { existing ->
            _status.value = ChainStatus(
                state = TunnelState.Error,
                detail = UiText.Resource(
                    Res.string.tunnel_error_relay_already_running_pid,
                    listOf(existing.pid()),
                ),
            )
            return null
        }
        // The rendered config carries credentials — keep it owner-only and ephemeral.
        val cfg = File.createTempFile("vpn-chain-", ".json").apply {
            setReadable(false, false)
            setReadable(true, true)
            deleteOnExit()
            writeText(configJson)
        }
        runtimeDir.mkdirs()

        val proc = try {
            ProcessBuilder(singBoxBin, "run", "-c", cfg.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(relayLog) // sing-box output → shared file we tail
                .start()
        } catch (e: IOException) {
            cfg.delete()
            _status.value = ChainStatus(
                state = TunnelState.Error,
                detail = UiText.Resource(Res.string.tunnel_error_cannot_launch, listOf(singBoxBin)),
            )
            return null
        }
        process = proc
        writePidFile(proc.pid())
        writeSessionState()

        readerJob = scope.launch(Dispatchers.IO) {
            val exit = proc.waitFor()
            cfg.delete()
            removePidFileIfMatches(proc.pid())
            // A non-zero exit while we still believe we're up means the tunnel died.
            val state = _status.value.state
            if (state == TunnelState.Connecting || state == TunnelState.Connected) {
                // Only blame CAP_NET_ADMIN when the log actually says so — a nonzero
                // exit in TUN mode can just as easily be a stale interface, a bad
                // WireGuard peer, or anything else, and mislabeling it wastes time.
                val permissionDenied = exit != 0 && tunMode && recentLogIndicatesPermissionDenied()
                if (exit != 0) {
                    emitRecentLog() // surface sing-box's failure reason
                    if (permissionDenied) {
                        emitLog(Res.string.tunnel_error_tun_privilege_hint)
                    }
                }
                stopLogTail()
                // A crash after Connected keeps the kill switch engaged — that leak is
                // exactly what it exists to stop. A startup that never got there
                // protected nothing, so leave networking as we found it instead.
                if (state == TunnelState.Connecting) disengageKillSwitch()
                _status.value = ChainStatus(
                    state = if (exit == 0) TunnelState.Disconnected else TunnelState.Error,
                    detail = when {
                        exit == 0 -> null
                        permissionDenied -> UiText.Resource(Res.string.tunnel_error_tun_privilege_hint)
                        killSwitchEngaged ->
                            UiText.Resource(Res.string.tunnel_error_exit_kill_switch_engaged, listOf(exit))
                        else -> UiText.Resource(Res.string.tunnel_error_exit_generic, listOf(exit))
                    },
                )
            }
        }
        return proc
    }

    private suspend fun awaitReady(proc: Process, ready: () -> Boolean) {
        repeat(STARTUP_POLLS) {
            if (!proc.isAlive) return
            if (ready()) {
                markConnected(adopted = false)
                return
            }
            delay(STARTUP_POLL_MS)
        }
        // Still alive but never signalled ready — surface it rather than hang.
        if (proc.isAlive && _status.value.state == TunnelState.Connecting) {
            markConnected(adopted = false)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        startStopLock.withLock {
            // Forced: after a GUI restart this process didn't install the table but
            // still has to be able to restore networking — Disconnect is the user's
            // documented way out of a fail-closed state.
            disengageKillSwitch(force = true)
            // Set before killing so the reader job doesn't report the shutdown as a crash.
            _status.value = ChainStatus(state = TunnelState.Disconnected)
            readerJob?.cancel()
            readerJob = null
            process?.let { own ->
                own.destroy()
                if (!own.waitFor(PROCESS_EXIT_TIMEOUT_S, TimeUnit.SECONDS)) own.destroyForcibly()
            }
            process = null
            // Adopted relay (the CLI's, or a previous GUI run): stop it via the shared
            // pidfile so Disconnect is one consistent control across both front-ends.
            livePidFileProcess()?.let { adopted ->
                adopted.destroy()
                runCatching { adopted.onExit().get(PROCESS_EXIT_TIMEOUT_S, TimeUnit.SECONDS) }
                if (adopted.isAlive) adopted.destroyForcibly()
            }
            stopLogTail()
            removePidFile()
            Unit
        }
    }

    /** Reflects relay up/down driven from outside this process (the CLI, or a
     *  previous GUI run whose relay outlived it). */
    private suspend fun pollExternalState() {
        while (scope.isActive) {
            delay(POLL_INTERVAL_MS)
            if (process?.isAlive == true) continue // we own it; the reader tracks it
            if (!startStopLock.tryLock()) continue // a connect/disconnect is mid-flight
            try {
                when (_status.value.state) {
                    TunnelState.Disconnected, TunnelState.Error -> adoptRunningSession()
                    TunnelState.Connected ->
                        if (detectRunningSession() == null) {
                            stopLogTail()
                            _status.value = ChainStatus(state = TunnelState.Disconnected)
                        }
                    TunnelState.Connecting -> Unit
                }
            } finally {
                startStopLock.unlock()
            }
        }
    }

    private fun markConnected(adopted: Boolean) {
        val realProtonAppUp = protonInterfaceUp()
        _status.value = ChainStatus(
            state = TunnelState.Connected,
            // Entry is via Proton whether sing-box dialed the WireGuard entry
            // itself, or the real Proton app happens to be the OS default route.
            entryThroughProton = tunHasEntry || realProtonAppUp,
            // Two independent mechanisms, depending on which entry-hop mode is
            // active: relay-only leans on the real Proton app's own kill switch;
            // the WG-entry-hop path gets its own nftables helper instead.
            killSwitch = when {
                !tunHasEntry ->
                    if (realProtonAppUp) KillSwitchState.Active else KillSwitchState.ProtonAppRequired
                killSwitchEngaged -> KillSwitchState.Active
                !killSwitchEnabled -> KillSwitchState.Disabled
                // Enabled and attempted, but the helper never came back happy.
                else -> KillSwitchState.HelperUnavailable
            },
            detail = when {
                tunMode && adopted -> UiText.Resource(Res.string.tunnel_status_tun_adopted)
                tunMode -> UiText.Resource(Res.string.tunnel_status_tun)
                adopted -> UiText.Resource(Res.string.tunnel_status_sharing_cli)
                else -> null
            },
        )
        startLogTail()
        startStatsPoll()
        scope.launch(Dispatchers.IO) {
            // The first request after a fresh relay often times out while the
            // VLESS connection warms up, so retry a few times. In TUN mode all
            // traffic is captured, so probe directly; in proxy mode go via SOCKS.
            repeat(EXIT_IP_ATTEMPTS) {
                if (_status.value.state != TunnelState.Connected) return@launch
                val exitIp = if (tunMode) probeExitIpDirect() else probeExitIp(proxyPort)
                if (exitIp != null) {
                    if (_status.value.state == TunnelState.Connected) {
                        _status.value = _status.value.copy(exitIp = exitIp)
                    }
                    return@launch
                }
                delay(EXIT_IP_RETRY_MS)
            }
        }
    }

    /** Follows the shared relay.log so the Logs screen shows output for owned and
     *  adopted relays alike, seeding with the recent history first. */
    private fun startLogTail() {
        logTailJob?.cancel()
        logTailJob = scope.launch(Dispatchers.IO) {
            var pos = if (relayLog.exists()) {
                runCatching { relayLog.readLines() }.getOrDefault(emptyList())
                    .takeLast(LOG_TAIL_LINES).forEach { emitLine(it) }
                relayLog.length()
            } else {
                0L
            }
            while (isActive) {
                delay(LOG_FOLLOW_MS)
                if (!relayLog.exists()) continue
                val len = relayLog.length()
                if (len < pos) pos = 0 // file was truncated/rotated
                if (len > pos) {
                    RandomAccessFile(relayLog, "r").use { raf ->
                        raf.seek(pos)
                        var line = raf.readLine()
                        while (line != null) {
                            emitLine(line)
                            line = raf.readLine()
                        }
                        pos = raf.filePointer
                    }
                }
            }
        }
    }

    /**
     * Adopted CLI relays render no clash API, so this simply never starts and
     * the readout stays empty rather than reporting zeroes as though measured.
     */
    private fun startStatsPoll() {
        statsJob?.cancel()
        val api = clashApi ?: return
        val startedAt = currentTimeMillis()
        _stats.value = SessionStats(connectedSinceMillis = startedAt)
        statsJob = scope.launch(Dispatchers.IO) {
            var lastTotals: TrafficTotals? = null
            var lastSampleAt = startedAt
            while (isActive && _status.value.state == TunnelState.Connected) {
                val totals = ClashStatsClient.fetchTotals(api)
                val now = currentTimeMillis()
                if (totals != null) {
                    val previous = lastTotals
                    _stats.value = SessionStats(
                        connectedSinceMillis = startedAt,
                        uplinkBytes = totals.uplinkBytes,
                        downlinkBytes = totals.downlinkBytes,
                        uplinkBytesPerSecond = previous?.let {
                            rateBytesPerSecond(it.uplinkBytes, totals.uplinkBytes, now - lastSampleAt)
                        } ?: 0,
                        downlinkBytesPerSecond = previous?.let {
                            rateBytesPerSecond(it.downlinkBytes, totals.downlinkBytes, now - lastSampleAt)
                        } ?: 0,
                    )
                    lastTotals = totals
                    lastSampleAt = now
                }
                delay(STATS_POLL_MS)
            }
        }
    }

    private fun stopStatsPoll() {
        statsJob?.cancel()
        statsJob = null
        _stats.value = SessionStats()
    }

    private fun stopLogTail() {
        stopStatsPoll()
        logTailJob?.cancel()
        logTailJob = null
    }

    private fun emitRecentLog() =
        runCatching { relayLog.readLines().takeLast(LOG_TAIL_LINES).forEach { emitLine(it) } }

    private fun recentLogIndicatesPermissionDenied(): Boolean =
        runCatching { relayLog.readLines().takeLast(LOG_TAIL_LINES) }.getOrDefault(emptyList())
            .any { PERMISSION_DENIED_REGEX.containsMatchIn(it) }

    /** sing-box colorizes output even to a file; strip ANSI so the UI stays clean. */
    private fun emitLine(line: String) = _logs.tryEmit(line.replace(ANSI_REGEX, ""))

    private fun isRelayListening(port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS); true }
        }.getOrDefault(false)

    /** Best-effort exit IP via the local SOCKS5 proxy, like `vpn-chain status`. */
    private fun probeExitIp(port: Int): String? = runCatching {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val conn = URI("https://api.ipify.org").toURL().openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.inputStream.bufferedReader().use { it.readText().trim() }.ifBlank { null }
    }.getOrNull()

    /** In TUN mode all traffic is already captured, so probe the exit IP directly. */
    private fun probeExitIpDirect(): String? = runCatching {
        val conn = URI("https://api.ipify.org").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.inputStream.bufferedReader().use { it.readText().trim() }.ifBlank { null }
    }.getOrNull()

    private fun protonInterfaceUp(): Boolean =
        runCatching { NetworkInterface.getByName(PROTON_INTERFACE)?.isUp == true }.getOrDefault(false)

    /** True once sing-box has created its TUN (identified by the fixed tun address). */
    private fun tunInterfaceUp(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            nif.interfaceAddresses.any { it.address?.hostAddress == TUN_ADDRESS }
        }
    }.getOrDefault(false)

    private fun writePidFile(pid: Long) = runCatching {
        runtimeDir.mkdirs()
        pidFile.writeText(pid.toString())
    }

    private fun readPidFile(): Long? =
        runCatching { pidFile.readText().trim().toLong() }.getOrNull()

    /**
     * The pidfile's process, but only when it still looks like our relay. PIDs are
     * recycled, so a stale file must never be mistaken for a running tunnel — and
     * an unreadable command line is treated as a match, since wrongly concluding
     * "nothing is running" is the expensive mistake here.
     */
    private fun livePidFileProcess(): ProcessHandle? {
        val pid = readPidFile() ?: return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!handle.isAlive) return null
        val command = handle.info().command().orElse(null)
        return if (command == null || command.contains("sing-box")) handle else null
    }

    private fun writeSessionState() = runCatching {
        runtimeDir.mkdirs()
        stateFile.writeText(
            RelaySession(
                tun = tunMode,
                entry = tunHasEntry,
                killSwitch = killSwitchEngaged,
                killSwitchEnabled = killSwitchEnabled,
            ).format(),
        )
    }

    private fun readSessionState(): RelaySession? =
        runCatching { RelaySession.parse(stateFile.readText()) }.getOrNull()

    private fun removePidFile() = runCatching {
        pidFile.delete()
        stateFile.delete()
    }

    private fun removePidFileIfMatches(pid: Long) {
        if (readPidFile() == pid) removePidFile()
    }

    private companion object {
        const val DEFAULT_PROXY_PORT = 1080
        const val PROTON_INTERFACE = "proton0"
        const val TUN_ADDRESS = "10.19.19.1"
        const val POLL_INTERVAL_MS = 3_000L
        const val STATS_POLL_MS = 1_000L
        const val PROCESS_EXIT_TIMEOUT_S = 5L
        const val PROBE_TIMEOUT_MS = 400
        const val HTTP_TIMEOUT_MS = 10_000
        const val STARTUP_POLLS = 40
        const val STARTUP_POLL_MS = 300L
        const val EXIT_IP_ATTEMPTS = 4
        const val EXIT_IP_RETRY_MS = 2_000L
        const val LOG_TAIL_LINES = 200
        const val LOG_FOLLOW_MS = 500L
        val ANSI_REGEX = Regex("\u001B\\[[0-9;]*m")
        val PERMISSION_DENIED_REGEX = Regex("operation not permitted|TUNSETIFF", RegexOption.IGNORE_CASE)
    }
}
