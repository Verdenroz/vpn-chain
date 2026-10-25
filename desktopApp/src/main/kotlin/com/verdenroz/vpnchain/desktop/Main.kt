package com.verdenroz.vpnchain.desktop

import com.verdenroz.vpnchain.desktop.cli.runCli
import kotlin.system.exitProcess

// Kept apart from the window code so the CLI path never loads a Compose or Koin class.
fun main(args: Array<String>) {
    if (args.isNotEmpty()) exitProcess(runCli(args.asList()))
    runGui()
}
