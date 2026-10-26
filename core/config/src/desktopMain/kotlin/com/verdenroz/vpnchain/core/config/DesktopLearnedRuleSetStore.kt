package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.common.PrivateStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Beside the rendered config under systemd, where the relay's own user reads
 * it through the `vpn-chain` group; in the owner-only config directory when
 * the app spawns sing-box itself.
 */
class DesktopLearnedRuleSetStore : LearnedRuleSetStore {

    private val managed: Boolean get() = SystemdRelay.available

    private val dir: File
        get() = if (managed) SystemdRelay.runDir else PrivateStorage.configDir

    override fun path(kind: RuleSetKind): String =
        File(dir, LearnedRuleSet.fileName(kind)).absolutePath

    override fun write(kind: RuleSetKind, rules: LearnedRules) {
        val target = File(path(kind)).toPath()
        val staged = if (managed) {
            target.resolveSibling("${target.fileName}.tmp").also {
                Files.deleteIfExists(it)
                Files.createFile(it, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")))
            }
        } else {
            PrivateStorage.createTempFile(dir, "${target.fileName}.", ".tmp").toPath()
        }
        Files.writeString(staged, LearnedRuleSet.render(rules))
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
