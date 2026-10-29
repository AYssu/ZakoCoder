package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "http://user:secret@127.0.0.1:8080",   // userInfo
            "http://user@127.0.0.1:8080",          // empty password userInfo
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

    @Test fun aptWorkaroundOnlyAppliesToHttpProxies() {
        assertTrue(NetworkProxy.aptAddrConfigWorkaround("http://127.0.0.1:8080"))
        assertFalse(NetworkProxy.aptAddrConfigWorkaround("socks5://127.0.0.1:1080"))
        assertFalse(NetworkProxy.aptAddrConfigWorkaround(""))
    }
}
