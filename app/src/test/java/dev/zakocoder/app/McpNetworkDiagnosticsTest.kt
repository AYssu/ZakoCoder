package dev.zakocoder.app

import dev.zakocoder.app.mcp.*
import java.net.*
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class McpNetworkDiagnosticsTest {
    @Test fun mapsSafeNetworkCategoriesWithoutRetainingUpstreamText() {
        val secret = "not-a-real-token"
        listOf(UnknownHostException(secret) to McpFailureReason.Dns,
            ConnectException(secret) to McpFailureReason.Connect,
            NoRouteToHostException(secret) to McpFailureReason.Connect,
            SSLHandshakeException(secret) to McpFailureReason.Tls,
            IOException(secret) to McpFailureReason.Network).forEach { (failure, reason) ->
            val diagnostic = McpDiagnostics.network(failure)
            assertEquals(reason, diagnostic.reason)
            assertFalse(diagnostic.toString().contains(secret))
            assertNull(diagnostic.cause)
        }
        assertEquals(McpProblem.Timeout, McpDiagnostics.network(SocketTimeoutException(secret)).problem)
    }
}
