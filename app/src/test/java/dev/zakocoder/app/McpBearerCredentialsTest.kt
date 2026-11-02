package dev.zakocoder.app

import dev.zakocoder.app.mcp.McpBearerCredentials
import org.junit.Assert.*
import org.junit.Test

class McpBearerCredentialsTest {
    @Test fun tokenProducesOneBearerHeaderAndPreservesOtherHeaders() {
        assertEquals(mapOf("X-Client" to "fixture", "Authorization" to "Bearer fixture-token"),
            McpBearerCredentials.headers("fixture-token", mapOf("X-Client" to "fixture")))
        assertEquals(mapOf("Authorization" to "Bearer fixture-token"), McpBearerCredentials.headers(" bearer fixture-token ", emptyMap()))
        assertTrue(McpBearerCredentials.headers("  ", emptyMap()).isEmpty())
    }
    @Test fun importedBearerCanBeEditedOrClearedWithoutTouchingBasicAuth() {
        val fields = McpBearerCredentials.split(mapOf("authorization" to "Bearer fixture-token", "X-Client" to "fixture"))
        assertEquals("fixture-token", fields.token)
        assertEquals(mapOf("X-Client" to "fixture"), fields.otherHeaders)
        assertEquals(mapOf("X-Client" to "fixture"), McpBearerCredentials.headers("", fields.otherHeaders))
        val basic = mapOf("Authorization" to "Basic fixture")
        assertEquals(basic, McpBearerCredentials.split(basic).otherHeaders)
        assertEquals(basic, McpBearerCredentials.headers("", basic))
        assertFalse(fields.toString().contains("fixture-token"))
    }
    @Test fun rejectsInjectionAndConflictingAuthorizationInsteadOfSilentlyReplacingIt() {
        for (token in listOf("foo\r\nX: bar", "two tokens", "foo\u0000bar", "x".repeat(8186))) {
            assertTrue(runCatching { McpBearerCredentials.headers(token, emptyMap()) }.isFailure)
        }
        assertTrue(runCatching { McpBearerCredentials.headers("fixture", mapOf("AUTHORIZATION" to "Basic fixture")) }.isFailure)
    }
}
