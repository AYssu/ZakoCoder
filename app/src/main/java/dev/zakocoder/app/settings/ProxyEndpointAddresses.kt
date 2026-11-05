package dev.zakocoder.app.settings

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Selects one resolved node address without trusting DNS order. A TCP reachability probe avoids
 * pinning the kernel to an unreachable first IPv4 address. IPv4 addresses are attempted first only
 * as a compatibility preference; every distinct address remains a fallback.
 */
internal object ProxyEndpointAddresses {
    fun selectReachable(
        addresses: List<InetAddress>,
        port: Int,
        timeoutMillis: Int = 1200,
        probe: (InetAddress, Int, Int) -> Boolean = ::tcpReachable,
    ): String? {
        if (port !in 1..65535) return null
        val timeout = timeoutMillis.coerceIn(100, 3000)
        return addresses
            .distinctBy { it.hostAddress }
            .sortedByDescending { it is Inet4Address }
            .firstOrNull { address -> runCatching { probe(address, port, timeout) }.getOrDefault(false) }
            ?.hostAddress
    }

    private fun tcpReachable(address: InetAddress, port: Int, timeoutMillis: Int): Boolean =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            true
        }
}
