package com.fsodano.openchamber.webstorm

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

object HttpClients {
    /**
     * Loopback traffic bypasses the IDE's proxy selector. Everything else uses
     * the IDE or system proxy, which the external server URL setting needs.
     */
    fun loopbackAwareProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host
            if (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]") {
                return listOf(Proxy.NO_PROXY)
            }
            return ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            ProxySelector.getDefault()?.connectFailed(uri, sa, ioe)
        }
    }
}
