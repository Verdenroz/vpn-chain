package com.verdenroz.vpnchain.core.config

import java.io.File

/** [dir] is the working directory libbox runs in, which only this app can read. */
class AndroidLearnedRuleSetStore(private val dir: File) : LearnedRuleSetStore {

    override fun path(kind: RuleSetKind): String =
        File(dir, LearnedRuleSet.fileName(kind)).absolutePath

    override fun write(kind: RuleSetKind, rules: LearnedRules) =
        writeReplacing(File(path(kind)).toPath(), LearnedRuleSet.render(rules), "rw-------")
}
