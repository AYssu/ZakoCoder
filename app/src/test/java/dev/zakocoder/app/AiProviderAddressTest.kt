package dev.zakocoder.app

import dev.zakocoder.app.ai.AiConfigStore
import org.junit.Assert.*
import org.junit.Test

class AiProviderAddressTest {
    @Test fun acceptsExplicitLocalAndPrivateHttpWithoutUpgradingOrDroppingThePort() {
        listOf("localhost", "127.0.0.1", "[::1]", "10.0.0.1", "10.255.255.254", "172.16.0.1",
            "172.31.255.254", "192.168.0.1", "192.168.255.254").forEach { host ->
            val base = "http://$host:8765/gateway/v1"
            assertEquals(base, AiConfigStore.endpoint(" $base/chat/completions/ "))
            assertEquals(base, AiConfigStore.endpoint("$base/responses"))
            assertEquals(base, AiConfigStore.endpoint("$base/messages"))
            assertEquals(base, AiConfigStore.endpoint("$base/models"))
        }
        assertEquals("http://localhost/v1", AiConfigStore.endpoint("http://localhost/v1"))
        assertEquals("http://LOCALHOST:8080/v1", AiConfigStore.endpoint("http://LOCALHOST:8080/v1"))
    }
    @Test fun acceptsHttpDomainsAndPublicIpAddressesWithoutDnsOrLocationRestrictions() {
        listOf("api.example.org", "localhost.example.org", "gateway.local", "8.8.8.8", "172.15.255.255",
            "172.32.0.1", "192.169.0.1", "169.254.1.1", "100.64.0.1", "[2001:db8::1]", "[fd00::1]")
            .forEach { host ->
                val base = "http://$host:8765/gateway/v1"
                assertEquals(base, AiConfigStore.endpoint("$base/chat/completions"))
                assertEquals(base, AiConfigStore.endpoint("$base/responses"))
                assertEquals(base, AiConfigStore.endpoint("$base/messages"))
                assertEquals(base, AiConfigStore.endpoint("$base/models"))
            }
        assertEquals("http://api.example.org/v1", AiConfigStore.endpoint("http://api.example.org/v1"))
    }
    @Test fun originIncludesSchemeAndEffectivePortWithoutComparingPaths() {
        assertEquals(AiConfigStore.origin("http://localhost/v1"), AiConfigStore.origin("http://LOCALHOST:80/other"))
        assertEquals(AiConfigStore.origin("https://localhost/v1"), AiConfigStore.origin("https://localhost:443/other"))
        assertNotEquals(AiConfigStore.origin("https://localhost:8765/v1"), AiConfigStore.origin("http://localhost:8765/v1"))
        assertNotEquals(AiConfigStore.origin("http://localhost:80/v1"), AiConfigStore.origin("http://localhost:81/v1"))
    }
    @Test fun httpsAndExistingCredentialQueryFragmentAndPortRestrictionsRemain() {
        assertEquals("https://api.example.org/v1", AiConfigStore.endpoint("https://api.example.org/v1/chat/completions"))
        listOf("http://user:secret@127.0.0.1/v1", "http://localhost/v1?key=secret", "http://localhost/v1#secret",
            "http://localhost:0/v1", "http://localhost:65536/v1", "http://localhost:abc/v1", "http:///v1", "file:///v1",
            "http://user:secret@api.example.org/v1", "http://api.example.org/v1?key=secret",
            "http://api.example.org/v1#secret", "http://api.example.org:65536/v1", "http://10.0.0.256/v1",
            "http://[2001:db8::1", "http://api example.org/v1", "http://api.example.org/v1\nrequest",
            "https://user:secret@example.org/v1", "https://example.org/v1?key=secret", "http://localhost/v1\nrequest")
            .forEach { assertTrue(it, runCatching { AiConfigStore.endpoint(it) }.isFailure) }
    }
}
