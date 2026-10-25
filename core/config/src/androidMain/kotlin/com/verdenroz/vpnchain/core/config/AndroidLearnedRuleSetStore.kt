package com.verdenroz.vpnchain.core.config

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** [dir] is the working directory libbox runs in, which only this app can read. */
class AndroidLearnedRuleSetStore(private val dir: File) : LearnedRuleSetStore {

    override fun path(kind: RuleSetKind): String =
        File(dir, LearnedRuleSet.fileName(kind)).absolutePath

    override fun write(kind: RuleSetKind, rules: LearnedRules) {
        val target = File(path(kind))
        val staged = File(dir, "${target.name}.tmp").apply { writeText(LearnedRuleSet.render(rules)) }
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
