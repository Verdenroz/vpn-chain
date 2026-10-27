package com.verdenroz.vpnchain.core.ui

/** [domain] without its `www.`, unless [among] also lists that shorter host. */
fun bareHost(domain: String, among: Collection<String>): String {
    val bare = domain.removePrefix("www.")
    return if (bare != domain && bare in among) domain else bare
}
