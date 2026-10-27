package com.verdenroz.vpnchain.boot

/**
 * The same restart logic as [BootReceiver], registered separately because
 * `MY_PACKAGE_REPLACED` is delivered without `RECEIVE_BOOT_COMPLETED` — the
 * permission guard that keeps the boot receiver system-only would drop it.
 * The broadcast is exempt from background-start limits, and `specialUse` is
 * not a foreground service type barred from starting out of it.
 */
class PackageReplacedReceiver : BootReceiver()
