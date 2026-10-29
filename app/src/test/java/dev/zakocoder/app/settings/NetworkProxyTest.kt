package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class NetworkProxyTest {
    @Test fun acceptsHttpAndSocks5Endpoints() {
        val http = NetworkProxy.parse("http://127.0.0.1:8080")
        assertEquals(NetworkProxy.Spec("http", "127.0.0.1", 8080), http)
        assertFalse(http!!.isSocks)
        assertEquals("http://127.0.0.1:8080", http.endpoint)

        val socks = NetworkProxy.parse("socks5://127.0.0.1:1080")
        assertEquals(NetworkProxy.Spec("socks5", "127.0.0.1", 1080), socks)
        assertTrue(socks!!.isSocks)
        assertEquals("socks5://127.0.0.1:1080", socks.endpoint)

        // A single trailing slash is the only tolerated path; surrounding whitespace is trimmed.
        assertEquals(NetworkProxy.Spec("http", "127.0.0.1", 80), NetworkProxy.parse(" http://127.0.0.1:80/ "))
        // Port bounds are inclusive on both ends.
        assertEquals(1, NetworkProxy.parse("http://127.0.0.1:1")!!.port)
        assertEquals(65535, NetworkProxy.parse("socks5://127.0.0.1:65535")!!.port)
    }

    @Test fun blankSettingMeansDisabled() {
        assertNull(NetworkProxy.parse(""))
        assertNull(NetworkProxy.parse("   "))
        assertNull(NetworkProxy.parse("\t"))
    }

    @Test fun rejectsCredentialsPathsAndMalformedEndpoints() {
        listOf(
            "http://user:secret@127.0.0.1:8080",   // userInfo on HTTP
            "http://user@127.0.0.1:8080",          // empty password userInfo on HTTP
            "http://127.0.0.1:8080/subpath",       // path
            "http://127.0.0.1:8080/subpath/",      // deeper path
            "http://127.0.0.1:8080?timeout=1",     // query
            "http://127.0.0.1:8080#fragment",      // fragment
            "http://127.0.0.1",                    // missing port
            "http://127.0.0.1:",                   // empty port
            "http://127.0.0.1:0",                  // port 0
            "http://127.0.0.1:65536",              // port above range
            "http://127.0.0.1:abc",                // non-numeric port
            "https://127.0.0.1:443",               // unsupported scheme
            "socks5h://127.0.0.1:1080",            // unsupported scheme, no remote DNS support
            "ftp://127.0.0.1:21",                  // unsupported scheme
            "socks4://127.0.0.1:1080",             // unsupported scheme
            "127.0.0.1:8080",                      // missing scheme
            "not a proxy",                         // spaces / junk
            "http://:8080",                        // missing host
            "http://127.0.0.1:8080\nsecret",       // control character
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { NetworkProxy.parse(raw) }
        }
    }

    @Test fun acceptsSocks5CredentialsWithoutLeakingThemToEndpointOrToString() {
        val spec = NetworkProxy.parse("socks5://user:secret@127.0.0.1:1080")!!
        assertEquals("socks5", spec.scheme)
        assertEquals("127.0.0.1", spec.host)
        assertEquals(1080, spec.port)
        assertEquals("user", spec.username)
        assertEquals("secret", spec.password)
        assertTrue(spec.hasCredentials)
        // The persisted endpoint must never carry credentials, and the UI form masks the password.
        assertEquals("socks5://127.0.0.1:1080", spec.endpoint)
        assertEquals("socks5://user:***@127.0.0.1:1080", spec.display)
        assertEquals("socks5://user:secret@127.0.0.1:1080", spec.proxyUrl)
        // toString() must never print the secret.
        assertFalse(spec.toString().contains("secret"))
        assertFalse(spec.toString().contains("user"))

        val credentials = NetworkProxy.credentials("socks5://user:secret@127.0.0.1:1080")
        assertNotNull(credentials)
        assertEquals("user", credentials!!.username)
        assertEquals("secret", credentials.password)
        assertFalse(credentials.toString().contains("secret"))
        assertNull(NetworkProxy.credentials("socks5://127.0.0.1:1080"))
        assertNull(NetworkProxy.credentials("http://127.0.0.1:8080"))
        assertNull(NetworkProxy.credentials(""))

        // javaProxy keeps the same SOCKS address; credentials travel through the JDK authenticator.
        val javaProxy = NetworkProxy.javaProxy("socks5://user:secret@127.0.0.1:1080")!!
        assertEquals(Proxy.Type.SOCKS, javaProxy.type())
        assertEquals(InetSocketAddress("127.0.0.1", 1080), javaProxy.address())
    }

    @Test fun decodesPercentEncodedCredentialsAndReencodesThemForConsumers() {
        val raw = "socks5://user%40corp:p%40ss%3Aword%2Fwith%20space@127.0.0.1:1080"
        val spec = NetworkProxy.parse(raw)!!
        assertEquals("user@corp", spec.username)
        assertEquals("p@ss:word/with space", spec.password)
        assertEquals("socks5://127.0.0.1:1080", spec.endpoint)
        assertEquals("socks5://user%40corp:***@127.0.0.1:1080", spec.display)
        assertEquals(raw, spec.proxyUrl)

        // An encoded ':' stays part of the username instead of splitting the pair.
        val colon = NetworkProxy.parse("socks5://user%3Aname:pa%3Ass@127.0.0.1:1080")!!
        assertEquals("user:name", colon.username)
        assertEquals("pa:ss", colon.password)
        // '+' is a literal character in a user-info component, not an encoded space.
        assertEquals("a+b", NetworkProxy.parse("socks5://a+b:c+d@127.0.0.1:1080")!!.username)
        assertEquals("c+d", NetworkProxy.parse("socks5://a+b:c+d@127.0.0.1:1080")!!.password)

        // Exactly 255 characters are accepted on both fields.
        val longest = NetworkProxy.parse("socks5://${"u".repeat(255)}:${"p".repeat(255)}@127.0.0.1:1080")!!
        assertEquals(255, longest.username!!.length)
        assertEquals(255, longest.password!!.length)
    }

    @Test fun rejectsIncompleteOrUnsafeCredentials() {
        listOf(
            "socks5://user@127.0.0.1:1080",                        // password missing
            "socks5://user:@127.0.0.1:1080",                       // password empty
            "socks5://:secret@127.0.0.1:1080",                     // username empty
            "socks5://@127.0.0.1:1080",                            // user info empty
            "socks5://${"u".repeat(256)}:secret@127.0.0.1:1080",   // username too long
            "socks5://user:${"p".repeat(256)}@127.0.0.1:1080",     // password too long
            "socks5://us%0Aer:secret@127.0.0.1:1080",              // control character in username
            "socks5://user:sec%00ret@127.0.0.1:1080",              // control character in password
            "socks5://user%ZZ:secret@127.0.0.1:1080",              // malformed percent escape
            "socks5://user:secret@127.0.0.1:1080/extra",           // path alongside credentials
            "http://user:secret@127.0.0.1:8080",                   // HTTP never accepts credentials
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { NetworkProxy.parse(raw) }
        }
    }

    @Test fun mapsJavaProxyTypeWithoutCredentials() {
        assertNull(NetworkProxy.javaProxy(""))
        val http = NetworkProxy.javaProxy("http://127.0.0.1:8080")!!
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(InetSocketAddress("127.0.0.1", 8080), http.address())

        val socks = NetworkProxy.javaProxy("socks5://127.0.0.1:1080")!!
        assertEquals(Proxy.Type.SOCKS, socks.type())
        assertEquals(InetSocketAddress("127.0.0.1", 1080), socks.address())
    }

    @Test fun guestEnvironmentNeverFakesHttpProxyForSocks5() {
        assertEquals(emptyList<String>(), NetworkProxy.guestEnvironment(""))

        val http = NetworkProxy.guestEnvironment("http://127.0.0.1:8080")
        assertEquals(listOf("http_proxy=http://127.0.0.1:8080", "https_proxy=http://127.0.0.1:8080",
            "HTTP_PROXY=http://127.0.0.1:8080", "HTTPS_PROXY=http://127.0.0.1:8080", "no_proxy=localhost,127.0.0.1"), http)

        val socks = NetworkProxy.guestEnvironment("socks5://127.0.0.1:1080")
        assertEquals(listOf("ALL_PROXY=socks5://127.0.0.1:1080", "all_proxy=socks5://127.0.0.1:1080",
            "no_proxy=localhost,127.0.0.1"), socks)
        assertTrue(socks.none { it.startsWith("http_proxy=") || it.startsWith("https_proxy=") })
    }

    @Test fun guestEnvironmentCarriesUrlEncodedSocks5Credentials() {
        val environment = NetworkProxy.guestEnvironment("socks5://user%40corp:p%40ss%3Aword@127.0.0.1:1080")
        assertEquals(listOf("ALL_PROXY=socks5://user%40corp:p%40ss%3Aword@127.0.0.1:1080",
            "all_proxy=socks5://user%40corp:p%40ss%3Aword@127.0.0.1:1080",
            "no_proxy=localhost,127.0.0.1"), environment)
        assertTrue(environment.none { it.startsWith("http_proxy=") || it.startsWith("https_proxy=") })

        // Stored credentials are injected only for SOCKS5; HTTP never gains them.
        val credentials = NetworkProxy.Credentials("user", "secret")
        assertEquals("socks5://user:secret@127.0.0.1:1080", NetworkProxy.withCredentials("socks5://127.0.0.1:1080", credentials))
        assertEquals("http://127.0.0.1:8080", NetworkProxy.withCredentials("http://127.0.0.1:8080", credentials))
        assertEquals("", NetworkProxy.withCredentials("", credentials))
        assertEquals("socks5://127.0.0.1:1080", NetworkProxy.withCredentials("socks5://127.0.0.1:1080", null))
        // Credentials are URL-encoded again when injected.
        assertEquals("socks5://a%40b:c%3Ad@127.0.0.1:1080",
            NetworkProxy.withCredentials("socks5://127.0.0.1:1080", NetworkProxy.Credentials("a@b", "c:d")))
    }

    @Test fun aptWorkaroundOnlyAppliesToHttpProxies() {
        assertTrue(NetworkProxy.aptAddrConfigWorkaround("http://127.0.0.1:8080"))
        assertFalse(NetworkProxy.aptAddrConfigWorkaround("socks5://127.0.0.1:1080"))
        assertFalse(NetworkProxy.aptAddrConfigWorkaround("socks5://user:secret@127.0.0.1:1080"))
        assertFalse(NetworkProxy.aptAddrConfigWorkaround(""))
    }
}
