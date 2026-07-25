package com.verdenroz.vpnchain.core.logging

actual fun createPlatformLogger(): Logger = object : Logger {
    override fun d(tag: String, message: String) = write("D", tag, message)
    override fun i(tag: String, message: String) = write("I", tag, message)
    override fun w(tag: String, message: String) = write("W", tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) {
        write("E", tag, message)
        throwable?.printStackTrace()
    }

    private fun write(level: String, tag: String, message: String) {
        System.err.println("[$level/$tag] $message")
    }
}
