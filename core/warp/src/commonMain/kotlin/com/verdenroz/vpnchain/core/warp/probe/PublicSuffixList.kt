package com.verdenroz.vpnchain.core.warp.probe

/**
 * The Public Suffix List's view of a host name: [registrableDomain] is the
 * public suffix plus one label, so `www.reddit.com` and `old.reddit.com` share
 * `reddit.com` while `a.github.io` and `b.github.io` stay apart. Suffixes from
 * the list's private section count too, since shared hosting is what they name.
 * [rules] are the list's lines, with any internationalised rule in ASCII form.
 */
class PublicSuffixList(rules: Sequence<String>) {
    private val exact = HashSet<String>()
    private val wildcards = HashSet<String>()
    private val exceptions = HashSet<String>()

    init {
        rules.map { it.trim().substringBefore(' ').lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .forEach { rule ->
                when {
                    rule.startsWith("!") -> exceptions += rule.drop(1)
                    rule.startsWith("*.") -> wildcards += rule.drop(2)
                    else -> exact += rule
                }
            }
    }

    /** Null when [host] is itself a public suffix, or has a single label. */
    fun registrableDomain(host: String): String? {
        val labels = host.lowercase().trimEnd('.').split('.')
        val suffixLabels = publicSuffixLength(labels)
        if (labels.size <= suffixLabels) return null
        return labels.takeLast(suffixLabels + 1).joinToString(".")
    }

    /** The longest matching rule wins, an exception over everything; unlisted is one label. */
    private fun publicSuffixLength(labels: List<String>): Int {
        for (start in labels.indices) {
            val candidate = labels.drop(start).joinToString(".")
            val rest = labels.drop(start + 1).joinToString(".")
            if (candidate in exceptions) return labels.size - start - 1
            if (candidate in exact || (rest.isNotEmpty() && rest in wildcards)) return labels.size - start
        }
        return 1
    }

    companion object {
        /** The list bundled with the app; parsed on first use. */
        val bundled: PublicSuffixList by lazy { PublicSuffixList(loadBundledPublicSuffixRules()) }
    }
}

/** The bundled `public_suffix_list.dat`, one rule or comment per line. */
internal expect fun loadBundledPublicSuffixRules(): Sequence<String>
