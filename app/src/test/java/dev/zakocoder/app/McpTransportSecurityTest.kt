package dev.zakocoder.app

import dev.zakocoder.app.mcp.McpConfigParser
import dev.zakocoder.app.mcp.McpException
import dev.zakocoder.app.mcp.McpFailureReason
import dev.zakocoder.app.mcp.McpServer
import dev.zakocoder.app.mcp.McpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTransportSecurityTest {
    @Test fun proxyAuthorizationIsRejectedBeforeOriginForwarding() {
        assertRejected("https://example.invalid/mcp", mapOf("proxy-authorization" to "Basic fixture"), McpFailureReason.Headers)
    }

    @Test fun authenticatedHttpsIsAllowed() {
        McpConfigParser.validate(server("https://example.invalid/mcp", mapOf("Authorization" to "Bearer fixture")))
    }

    /**
     * Plaintext HTTP stays a supported self-hosted bridge (LAN/loopback MCP servers), so credentials
     * over http must keep importing. Tightening this to https-only is a product decision, not a fix:
     * it would reject configurations documented in docs/ and already covered by device fixtures.
     */
    @Test fun plaintextHttpRemainsSupportedForSelfHostedBridges() {
        for (url in listOf("http://127.0.0.1:9127/mcp", "http://10.0.0.2:9127/mcp", "http://example.invalid/mcp")) {
            McpConfigParser.validate(server(url, mapOf("Authorization" to "Bearer fixture")))
        }
    }

    private fun assertRejected(url: String, headers: Map<String, String>, reason: McpFailureReason) {
        val failure = runCatching { McpConfigParser.validate(server(url, headers)) }.exceptionOrNull()
        assertTrue(failure is McpException)
        assertEquals(reason, (failure as McpException).reason)
    }

    private fun server(url: String, headers: Map<String, String> = emptyMap()) =
        McpServer(name = "fixture", transport = McpTransport.StreamableHttp, url = url, headers = headers)
}
