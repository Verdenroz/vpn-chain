package com.verdenroz.vpnchain.core.config

import java.io.File

/**
 * Where the relay reads and writes when systemd runs it instead of the app.
 *
 * The unit grants CAP_NET_ADMIN through `AmbientCapabilities`, so the sing-box
 * binary carries no file capability and a package upgrade can't silently strip
 * one. With no unit installed the app spawns sing-box itself and the user's own
 * paths apply instead.
 *
 * Lives here rather than in `core:tunnel` because the paths have to be baked
 * into the rendered config, which this module owns.
 */
object SystemdRelay {
    const val UNIT = "vpn-chain-relay.service"

    /** Matches `StateDirectory=` in the unit. */
    private const val STATE_DIR = "/var/lib/vpn-chain"

    val runDir = File("/run/vpn-chain")
    val configFile = File(runDir, "relay.json")
    val logFile = File(runDir, "relay.log")
    val cachePath = "$STATE_DIR/singbox-cache.db"

    /**
     * A packaged unit lives under `/usr/lib`; `/etc` covers a hand-installed or
     * overridden one. Checked per call: installing the unit shouldn't require
     * restarting the app to take effect.
     */
    val unitInstalled: Boolean
        get() = UNIT_PATHS.any { File(it, UNIT).exists() }

    /**
     * Installed *and* usable. Setup grants the `vpn-chain` group that makes the
     * handoff directory writable, and group membership never reaches a session
     * that was already logged in, so an installed unit is unusable until the
     * next login. Treating that as unavailable falls back to spawning sing-box
     * instead of failing the connect outright.
     */
    val available: Boolean
        get() = unitInstalled && runDir.canWrite()

    private val UNIT_PATHS = listOf("/usr/lib/systemd/system", "/etc/systemd/system")
}
