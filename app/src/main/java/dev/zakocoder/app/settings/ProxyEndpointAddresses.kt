package dev.zakocoder.app.settings

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException

/**
 * Selects one resolved node address without trusting DNS order. A TCP reachability probe avoids
 * pinning the kernel to an unreachable first IPv4 address. IPv4 addresses are attempted first only
 * as a compatibility preference; every distinct address remains a fallback within one overall
 * deadline. Callers pass [isActive] so a stopped or superseded start stops probing between
 * addresses; a probe that throws [CancellationException] propagates instead of looking unreachable.
 */
internal object ProxyEndpointAddresses {
    private const val DEFAULT_PER_ADDRESS_TIMEOUT_MILLIS = 1200
    private const val DEFAULT_TOTAL_TIMEOUT_MILLIS = 3000
    private const val MAX_TIMEOUT_MILLIS = 30000

    fun selectReachable(
        addresses: List<InetAddress>,
        port: Int,
        timeoutMillis: Int = DEFAULT_PER_ADDRESS_TIMEOUT_MILLIS,
        totalTimeoutMillis: Int = DEFAULT_TOTAL_TIMEOUT_MILLIS,
        isActive: () -> Boolean = { true },
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        probe: (InetAddress, Int, Int) -> Boolean = ::tcpReachable,
    ): String? {
        if (port !in 1..65535) return null
        val perAddressTimeout = timeoutMillis.coerceIn(100, 3000)
        val totalBudget = totalTimeoutMillis.coerceIn(1, MAX_TIMEOUT_MILLIS)
        val deadline = nowMillis() + totalBudget
        val ordered = addresses.distinctBy { it.hostAddress }.sortedByDescending { it is Inet4Address }
        for (address in ordered) {
            if (!isActive()) return null
            val remaining = deadline - nowMillis()
            if (remaining <= 0L) return null
            val attemptTimeout = minOf(perAddressTimeout.toLong(), remaining).toInt().coerceAtLeast(1)
            val reachable = try {
                probe(address, port, attemptTimeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (reachable) {
                if (!isActive()) return null
                return address.hostAddress
            }
        }
        return null
    }

    private fun tcpReachable(address: InetAddress, port: Int, timeoutMillis: Int): Boolean =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            true
        }
}
