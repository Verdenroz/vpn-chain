package com.verdenroz.vpnchain.core.tunnel

import com.verdenroz.vpnchain.core.config.SingBoxConfigFactory
import com.verdenroz.vpnchain.core.config.writeReplacing
import java.nio.file.Path

/**
 * Hands the managed relay its config and render stamp. Group-readable, not
 * owner-only: the service user reads both through the `vpn-chain` group. The
 * directory is writable by that user, so neither write follows a planted link.
 */
internal fun writeManagedConfig(configFile: Path, stampFile: Path, configJson: String) {
    writeReplacing(configFile, configJson, MANAGED_MODE)
    writeReplacing(stampFile, SingBoxConfigFactory.RENDER_SCHEMA.toString(), MANAGED_MODE)
}

private const val MANAGED_MODE = "rw-r-----"
