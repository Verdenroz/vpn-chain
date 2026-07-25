package com.verdenroz.vpnchain.core.common.autostart

/**
 * The per-OS text and commands that register the app to start at login.
 *
 * Kept pure and separate from the file/registry writing so the awkward parts —
 * quoting a path with spaces, plist escaping — are testable without touching
 * the host machine.
 */
internal object AutostartEntries {

    const val LABEL = "com.verdenroz.vpnchain"
    const val DISPLAY_NAME = "VPN Chain"
    const val REGISTRY_VALUE_NAME = "VpnChain"
    const val REGISTRY_RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""

    /**
     * Freedesktop autostart entry. `Exec` is quoted because an unquoted path
     * with a space parses as a command plus arguments.
     */
    fun linuxDesktopEntry(execPath: String): String = """
        [Desktop Entry]
        Type=Application
        Name=$DISPLAY_NAME
        Comment=Start the VPN chain when you log in
        Exec=${quoteForExec(execPath)}
        Icon=vpn-chain
        Terminal=false
        Categories=Network;Security;
        X-GNOME-Autostart-enabled=true
    """.trimIndent() + "\n"

    fun macLaunchAgentPlist(execPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>$LABEL</string>
            <key>ProgramArguments</key>
            <array>
                <string>${escapeXml(execPath)}</string>
            </array>
            <key>RunAtLoad</key>
            <true/>
        </dict>
        </plist>
    """.trimIndent() + "\n"

    /** Argv for `reg`, passed as a list so no shell ever parses the path. */
    fun windowsRegisterArgv(execPath: String): List<String> = listOf(
        "reg", "add", REGISTRY_RUN_KEY,
        "/v", REGISTRY_VALUE_NAME,
        "/t", "REG_SZ",
        // Windows itself re-parses this value, so the path needs its own quotes.
        "/d", "\"$execPath\"",
        "/f",
    )

    fun windowsUnregisterArgv(): List<String> = listOf(
        "reg", "delete", REGISTRY_RUN_KEY,
        "/v", REGISTRY_VALUE_NAME,
        "/f",
    )

    fun windowsQueryArgv(): List<String> = listOf(
        "reg", "query", REGISTRY_RUN_KEY,
        "/v", REGISTRY_VALUE_NAME,
    )

    private fun quoteForExec(path: String): String =
        if (path.any { it.isWhitespace() }) "\"$path\"" else path

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
