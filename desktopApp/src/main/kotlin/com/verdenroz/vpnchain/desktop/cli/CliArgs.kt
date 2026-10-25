package com.verdenroz.vpnchain.desktop.cli

internal sealed interface Command {
    data class Up(val profile: String?, val wait: Boolean, val timeoutSeconds: Int = DEFAULT_UP_TIMEOUT_SECONDS) : Command
    data object Down : Command
    data class Status(val watch: Boolean) : Command
    data object List : Command
    data class Use(val profile: String) : Command
    data class Logs(val lines: Int?) : Command
    data object Help : Command
    data object Version : Command
}

internal const val DEFAULT_UP_TIMEOUT_SECONDS = 60

internal data class Invocation(val command: Command, val json: Boolean = false)

internal class UsageException(message: String) : Exception(message)

internal val USAGE = """
    usage: vpn-chain <command> [--json]

      up [NAME] [--no-wait] [--timeout S]
                              connect, switching to NAME (a saved connection's name or id) first;
                              waits up to S seconds (default $DEFAULT_UP_TIMEOUT_SECONDS) while the engine redials
      down                    disconnect
      status [--watch]        show the chain's state; --watch follows it
      list                    list saved connections
      use NAME                make NAME the active connection
      logs [-n N]             print the newest N buffered log lines (default: all)

    --json prints machine-readable output. Run vpn-chain-gui for the window.
""".trimIndent()

internal fun parse(args: List<String>): Invocation {
    val json = "--json" in args
    val rest = args.filter { it != "--json" }
    val operands = Operands(rest.drop(1))
    val command = when (val name = rest.firstOrNull()) {
        null, "help", "-h", "--help" -> Command.Help
        "--version", "version" -> Command.Version
        "up" -> Command.Up(
            wait = !operands.flag("--no-wait"),
            timeoutSeconds = operands.count("--timeout") ?: DEFAULT_UP_TIMEOUT_SECONDS,
            profile = operands.optional(),
        )
        "down" -> Command.Down
        "status" -> Command.Status(watch = operands.flag("--watch"))
        "list" -> Command.List
        "use" -> Command.Use(operands.required("NAME"))
        "logs" -> Command.Logs(operands.count("-n"))
        else -> throw UsageException("unknown command: $name")
    }
    operands.finish()
    return Invocation(command, json)
}

private class Operands(args: List<String>) {
    private val remaining = args.toMutableList()

    fun flag(name: String): Boolean = remaining.remove(name)

    fun count(name: String): Int? {
        val at = remaining.indexOf(name).takeIf { it >= 0 } ?: return null
        val value = remaining.getOrNull(at + 1)?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw UsageException("$name needs a positive number")
        remaining.subList(at, at + 2).clear()
        return value
    }

    fun optional(): String? = remaining.firstOrNull { !it.startsWith("-") }?.also(remaining::remove)

    fun required(label: String): String = optional() ?: throw UsageException("missing $label")

    fun finish() {
        if (remaining.isNotEmpty()) throw UsageException("unexpected ${remaining.joinToString(" ")}")
    }
}
