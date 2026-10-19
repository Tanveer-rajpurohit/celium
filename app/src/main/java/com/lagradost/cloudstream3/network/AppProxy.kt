package com.lagradost.cloudstream3.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** Empty uses the platform ProxySelector and VPN routes. Credentials are deliberately unsupported. */
internal object AppProxy {
    const val PREFERENCE_KEY = "app_proxy_url"

    fun parse(value: String): Proxy? {
        val input = value.trim()
        if (input.isEmpty()) return null
        val uri = URI(input)
        require(uri.scheme == "http" || uri.scheme == "socks") { "Use http://host:port or socks://host:port" }
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) { "A host and port from 1 to 65535 are required" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) {
            "Proxy credentials, paths, queries, and fragments are unsupported"
        }
        val type = if (uri.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
    }
}
