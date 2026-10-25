package com.verdenroz.vpnchain.core.logging

import android.util.Log

actual fun createPlatformLogger(): Logger = object : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun i(tag: String, message: String) { Log.i(tag, message) }
    override fun w(tag: String, message: String, throwable: Throwable?) { Log.w(tag, message.withCause(throwable)) }
    override fun e(tag: String, message: String, throwable: Throwable?) { Log.e(tag, message.withCause(throwable)) }

    /** Only the class name: throwable messages and stacks can carry server addresses and ports. */
    private fun String.withCause(throwable: Throwable?) = if (throwable == null) this else "$this: ${throwable::class.simpleName}"
}
