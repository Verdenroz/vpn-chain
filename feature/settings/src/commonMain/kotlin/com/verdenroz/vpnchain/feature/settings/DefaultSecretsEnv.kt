package com.verdenroz.vpnchain.feature.settings

/**
 * Reads the CLI's `~/.config/vpn-chain/secrets.env` if present. Desktop can
 * import it directly; Android has no such path and always returns null.
 */
expect fun readDefaultSecretsEnv(): String?
