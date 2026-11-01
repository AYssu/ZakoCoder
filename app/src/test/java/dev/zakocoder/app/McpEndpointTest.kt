package dev.zakocoder.app

import dev.zakocoder.app.mcp.McpConfigParser
import dev.zakocoder.app.mcp.McpException
import dev.zakocoder.app.mcp.McpProblem
import org.junit.Assert.*
import org.junit.Test

/** Address policy only: these cases never resolve DNS or connect to any host. */
class McpEndpointTest {
    @Test fun httpAndHttpsAcceptPublicPrivateAndLoopbackDomainsAndIps() {
        for (scheme in listOf("http", "https")) {
            for (host in listOf("example.invalid", "gateway.lan", "localhost", "8.8.8.8", "10.0.0.2",
                "192.168.1.10", "172.16.0.1", "127.0.0.1", "[::1]", "[2001:db8::1]")) {
                val url = "$scheme://$host:9127/mcp"
                assertEquals(url, McpConfigParser.endpoint(url).toString())
            }
        }
    }

    @Test fun malformedAddressesAndEmbeddedSecretsRemainRejected() {
        for (url in listOf("", "example.invalid/mcp", "http:///mcp", "ftp://example.invalid/mcp",
            "http://example.invalid:0/mcp", "http://example.invalid:65536/mcp", "http://example.invalid:abc/mcp",
            "http://user:secret@example.invalid/mcp", "http://example.invalid/mcp?token=fixture",
            "http://example.invalid/mcp#fixture", "http://example invalid/mcp", "http://[2001:db8::1/mcp",
            "http://10.0.0.256/mcp", "http://example.invalid/mcp\nrequest")) {
            val failure = runCatching { McpConfigParser.endpoint(url) }.exceptionOrNull()
            assertTrue("Invalid MCP address must remain invalid: $url", failure is McpException)
            assertEquals(McpProblem.InvalidConfig, (failure as McpException).problem)
        }
    }

    @Test fun publicHttpRetainsOriginIsolationAcrossSchemesHostsAndEffectivePorts() {
        assertTrue(McpConfigParser.sameOrigin("http://gateway.example.invalid/mcp", "http://GATEWAY.example.invalid:80/messages?session=fixture"))
        assertTrue(McpConfigParser.sameOrigin("https://gateway.example.invalid/mcp", "https://gateway.example.invalid:443/messages"))
        assertFalse(McpConfigParser.sameOrigin("http://gateway.example.invalid/mcp", "https://gateway.example.invalid/messages"))
        assertFalse(McpConfigParser.sameOrigin("http://gateway.example.invalid/mcp", "http://other.example.invalid/messages"))
        assertFalse(McpConfigParser.sameOrigin("http://gateway.example.invalid:9127/mcp", "http://gateway.example.invalid:9128/messages"))
    }
}
