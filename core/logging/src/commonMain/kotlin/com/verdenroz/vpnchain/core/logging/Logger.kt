package com.verdenroz.vpnchain.core.logging

/** Minimal logging facade; Android backs it with Logcat, desktop with stderr. */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

expect fun createPlatformLogger(): Logger
