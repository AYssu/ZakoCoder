package dev.zakocoder.app

import dev.zakocoder.app.mcp.McpCleartextHosts
import org.junit.Assert.*
import org.junit.Test

class McpCleartextHostsTest {
    @Test fun allowsLoopbackAndLiteralPrivateIpv4BoundariesWithoutDns() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "[::1]", "::1", "10.0.0.2",
            "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255",
            "192.168.0.0", "192.168.255.255").forEach { assertTrue(it, McpCleartextHosts.allowed(it)) }
    }
    @Test fun rejectsPublicSpecialMalformedAndLookalikeHosts() {
        listOf("example.invalid", "10.0.0.2.example.invalid", "localhost.example.invalid", "8.8.8.8",
            "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "127.1", "2130706433", "10.1", "010.0.0.2",
            "10.0.0.256", "10.0.0.-1", "10.0.0.2.", "10.0.0.2\n", "0xa000002", "::ffff:10.0.0.2")
            .forEach { assertFalse(it, McpCleartextHosts.allowed(it)) }
    }
}
