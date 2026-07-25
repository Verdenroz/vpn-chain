package com.verdenroz.vpnchain.core.tunnel

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator

/** Kotlin-list-backed libbox iterators, and readers for the ones libbox hands us. */

internal class StringList(private val items: List<String>) : StringIterator {
    private var i = 0
    override fun hasNext() = i < items.size
    override fun len() = items.size
    override fun next(): String = items[i++]
}

internal class NetworkInterfaceList(
    private val items: List<NetworkInterface>,
) : NetworkInterfaceIterator {
    private var i = 0
    override fun hasNext() = i < items.size
    override fun next(): NetworkInterface = items[i++]
}

internal fun RoutePrefixIterator.readAll(): List<RoutePrefix> =
    buildList { while (hasNext()) add(next()) }

internal fun StringIterator.readAll(): List<String> =
    buildList { while (hasNext()) add(next()) }
