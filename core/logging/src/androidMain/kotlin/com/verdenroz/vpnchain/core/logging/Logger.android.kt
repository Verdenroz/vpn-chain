package com.verdenroz.vpnchain.core.logging

import android.util.Log

actual fun createPlatformLogger(): Logger = object : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun i(tag: String, message: String) { Log.i(tag, message) }
    override fun w(tag: String, message: String) { Log.w(tag, message) }
    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
