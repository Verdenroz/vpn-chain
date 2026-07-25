package com.verdenroz.vpnchain.boot

/**
 * The same restart logic as [BootReceiver], registered separately because
 * `MY_PACKAGE_REPLACED` is delivered without `RECEIVE_BOOT_COMPLETED` — the
 * permission guard that keeps the boot receiver system-only would drop it.
 */
class PackageReplacedReceiver : BootReceiver()
