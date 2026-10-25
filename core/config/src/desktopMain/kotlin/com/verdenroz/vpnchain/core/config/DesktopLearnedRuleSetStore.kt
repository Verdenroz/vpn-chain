package com.verdenroz.vpnchain.core.config

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Beside the rendered config under systemd, where the relay's own user reads
 * it through the `vpn-chain` group; in the user's config directory when the
 * app spawns sing-box itself.
 */
class DesktopLearnedRuleSetStore : LearnedRuleSetStore {

    private val dir: File
        get() = if (SystemdRelay.available) {
            SystemdRelay.runDir
        } else {
            userConfigDir()
        }

    override fun path(kind: RuleSetKind): String =
        File(dir, LearnedRuleSet.fileName(kind)).absolutePath

    override fun write(kind: RuleSetKind, rules: LearnedRules) {
        val target = File(path(kind)).toPath()
        val staged = target.resolveSibling("${target.fileName}.tmp")
        Files.deleteIfExists(staged)
        Files.createFile(staged, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")))
        Files.writeString(staged, LearnedRuleSet.render(rules))
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
