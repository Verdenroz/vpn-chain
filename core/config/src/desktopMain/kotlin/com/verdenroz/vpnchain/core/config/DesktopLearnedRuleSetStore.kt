package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.common.PrivateStorage
import java.io.File

/**
 * Beside the rendered config under systemd, where the relay's own user reads
 * it through the `vpn-chain` group; in the owner-only config directory when
 * the app spawns sing-box itself. The directory is chosen once per session, so
 * the files are always written where the running config looks for them; a
 * directory that stops being writable is an error, never a quiet move.
 */
class DesktopLearnedRuleSetStore(
    private val managed: () -> Boolean = { SystemdRelay.available },
    private val runDir: () -> File = { SystemdRelay.runDir },
    private val privateDir: () -> File = { PrivateStorage.configDir },
) : LearnedRuleSetStore {

    private class Session(val dir: File, val mode: String)

    @Volatile
    private var session: Session? = null

    override fun beginSession() {
        session = resolve()
    }

    override fun path(kind: RuleSetKind): String =
        File(current().dir, LearnedRuleSet.fileName(kind)).absolutePath

    override fun write(kind: RuleSetKind, rules: LearnedRules) {
        val session = current()
        writeReplacing(File(session.dir, LearnedRuleSet.fileName(kind)).toPath(), LearnedRuleSet.render(rules), session.mode)
    }

    private fun current(): Session = session ?: resolve().also { session = it }

    private fun resolve(): Session =
        if (managed()) Session(runDir(), GROUP_READABLE) else Session(privateDir(), OWNER_ONLY)

    private companion object {
        const val GROUP_READABLE = "rw-r-----"
        const val OWNER_ONLY = "rw-------"
    }
}
