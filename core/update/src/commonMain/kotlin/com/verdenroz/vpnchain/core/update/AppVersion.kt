package com.verdenroz.vpnchain.core.update

/** Whether [candidate] is a later `major.minor.patch` than [installed]; a name in any other shape never is. */
internal fun isNewer(candidate: String, installed: String): Boolean {
    val later = parts(candidate) ?: return false
    val current = parts(installed) ?: return false
    return later.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

private fun parts(version: String): List<Int>? =
    version.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size == 3 }
