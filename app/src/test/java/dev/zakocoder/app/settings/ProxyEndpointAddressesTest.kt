package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CancellationException

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

    @Test fun overallBudgetStopsLaterAddressesAndCapsAttemptTimeout() {
        val first = InetAddress.getByName("192.0.2.10")
        val second = InetAddress.getByName("192.0.2.11")
        val third = InetAddress.getByName("192.0.2.12")
        var now = 0L
        val attempts = mutableListOf<Pair<String?, Int>>()

        val selected = ProxyEndpointAddresses.selectReachable(
            addresses = listOf(first, second, third),
            port = 443,
            timeoutMillis = 3000,
            totalTimeoutMillis = 2500,
            nowMillis = { now },
            probe = { address, _, timeout ->
                attempts += address.hostAddress to timeout
                now += 1300
                false
            },
        )

        assertNull(selected)
        assertEquals(listOf(first.hostAddress to 2500, second.hostAddress to 1200), attempts)
    }

    @Test fun inactiveStartStopsAfterTheCurrentProbe() {
        val first = InetAddress.getByName("192.0.2.20")
        val second = InetAddress.getByName("192.0.2.21")
        var active = true
        val attempts = mutableListOf<String?>()

        val selected = ProxyEndpointAddresses.selectReachable(
            addresses = listOf(first, second),
            port = 443,
            isActive = { active },
            probe = { address, _, _ ->
                attempts += address.hostAddress
                active = false
                false
            },
        )

        assertNull(selected)
        assertEquals(listOf(first.hostAddress), attempts)
    }

    @Test fun ordinaryProbeFailureIsUnreachableButCancellationPropagates() {
        val address = InetAddress.getByName("192.0.2.30")
        assertNull(ProxyEndpointAddresses.selectReachable(listOf(address), 443) { _, _, _ ->
            throw java.io.IOException("down")
        })

        val cancelled = CancellationException("stopped")
        val thrown = assertThrows(CancellationException::class.java) {
            ProxyEndpointAddresses.selectReachable(listOf(address), 443) { _, _, _ -> throw cancelled }
        }
        assertSame(cancelled, thrown)
    }
}
