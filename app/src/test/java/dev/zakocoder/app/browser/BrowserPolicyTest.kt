package dev.zakocoder.app.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserPolicyTest {
    @Test fun onlyLoopbackAddressesSkipExternalOriginConfirmation() {
        for (raw in listOf("localhost:5173", "127.0.0.1:8000", "http://[::1]:3000/")) {
            assertTrue(raw, BrowserPolicy.address(raw).local)
            assertTrue(BrowserPolicy.address(raw).url.startsWith("http://"))
        }
        for (raw in listOf("http://10.0.0.2:5173", "https://localhost.evil.example", "http://127.0.0.1.evil.example")) {
            assertFalse(raw, BrowserPolicy.address(raw).local)
        }
    }

    @Test fun originsNormalizeSchemeHostAndDefaultPortsWithoutDroppingPathOrQuery() {
        val address = BrowserPolicy.address("HTTPS://EXAMPLE.COM:443/a%20b?q=1#part")
        assertEquals("https://example.com", address.origin)
        assertEquals("https://example.com/a%20b?q=1#part", address.url)
        assertEquals("http://example.com:8080", BrowserPolicy.address("http://example.com:8080").origin)
        assertEquals("https://example.com/", BrowserPolicy.address("example.com").url)
    }

    @Test fun rejectsPrivilegedSchemesCredentialsAndAmbiguousAddresses() {
        listOf("file:///etc/passwd", "content://provider/a", "javascript:alert(1)", "intent://host/#Intent",
            "data:text/html,hello", "https://user:pass@example.com", "http://example.com:65536/",
            "http://example.com:0/", "https://example.com\\@evil.example", "http://example.com/ a",
            "https://", "", "https://example.com/\n").forEach { raw ->
            try { BrowserPolicy.address(raw); fail("Accepted $raw") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun confirmationRemainsDistinctAcrossPortsAndTlsBoundaries() {
        assertNotEquals(BrowserPolicy.address("http://example.com").origin, BrowserPolicy.address("https://example.com").origin)
        assertNotEquals(BrowserPolicy.address("http://example.com:8080").origin, BrowserPolicy.address("http://example.com:8081").origin)
    }
}
