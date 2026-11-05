package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class ProxyEndpointAddressesTest {
    @Test fun selectsFirstReachableAddressAndKeepsIpv6Fallback() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4Down = InetAddress.getByName("192.0.2.1")
        val ipv4Up = InetAddress.getByName("192.0.2.2")
        val attempts = mutableListOf<String>()

        val selected = ProxyEndpointAddresses.selectReachable(
            listOf(ipv6, ipv4Down, ipv4Up, ipv4Down),
            port = 443,
            probe = { address, port, _ ->
                assertEquals(443, port)
                attempts.add(address.hostAddress.orEmpty())
                address == ipv4Up
            },
        )

        assertEquals(ipv4Up.hostAddress, selected)
        assertEquals(listOf(ipv4Down.hostAddress, ipv4Up.hostAddress), attempts)
    }

    @Test fun fallsBackToIpv6AndRejectsEveryFailedAddress() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("192.0.2.1")
        val selected = ProxyEndpointAddresses.selectReachable(listOf(ipv4, ipv6), 443) { address, _, _ ->
            address == ipv6
        }
        assertEquals(ipv6.hostAddress, selected)
        assertNull(ProxyEndpointAddresses.selectReachable(listOf(ipv4), 443) { _, _, _ -> false })
        assertNull(ProxyEndpointAddresses.selectReachable(listOf(ipv4), 0) { _, _, _ -> true })
    }
}
