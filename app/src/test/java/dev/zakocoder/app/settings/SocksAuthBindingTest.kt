package dev.zakocoder.app.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.Authenticator

/**
 * Unit matrix for the endpoint-bound SOCKS5 credential decision [NetworkProxy.credentialFor].
 *
 * These cases encode the maintainer's two reproductions and the switch/disable isolation
 * requirements without any network I/O:
 * - credentials are returned only for the exact bound SOCKS5 endpoint;
 * - an unrelated SOCKS5 endpoint gets null (no current-pair leak);
 * - an HTTP PROXY authentication challenge gets null, even for matching host/port;
 * - the JDK/Android `RequestorType.SERVER` quirk for SOCKS5 still receives the pair;
 * - switching A -> B atomically invalidates A and enables B, and disabling clears everything.
 */
class SocksAuthBindingTest {
    private var previousDefault: Authenticator? = null

    @Before
    fun setUp() {
        previousDefault = currentDefaultAuthenticator()
        NetworkProxy.configureSocksAuthentication(null)
    }

    @After
    fun tearDown() {
        NetworkProxy.configureSocksAuthentication(null)
        Authenticator.setDefault(previousDefault)
    }

    @Test
    fun noBindingNeverReturnsCredentials() {
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.PROXY))

        NetworkProxy.configureSocksAuthentication("")
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))

        // HTTP endpoints and credential-less SOCKS5 endpoints never create a binding.
        NetworkProxy.configureSocksAuthentication("http://127.0.0.1:8080")
        assertNull(credentialFor("127.0.0.1", 8080, "http", Authenticator.RequestorType.PROXY))
        assertNull(credentialFor("127.0.0.1", 8080, "SOCKS5", Authenticator.RequestorType.SERVER))

        NetworkProxy.configureSocksAuthentication("socks5://127.0.0.1:1080")
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
    }

    @Test
    fun returnsCredentialsOnlyForTheBoundSocks5Endpoint() {
        NetworkProxy.configureSocksAuthentication("socks5://user:secret@127.0.0.1:1080")

        // The real JDK callback shape for SOCKS5: protocol SOCKS5, requestor type reset to SERVER.
        val fromServerCallback = credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)
        assertEquals("user", fromServerCallback?.username)
        assertEquals("secret", fromServerCallback?.password)
        // Normal PROXY requestor type is accepted as well.
        assertEquals("user", credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.PROXY)?.username)
        // Protocol comparison is case-insensitive (JDK sends "SOCKS5").
        assertEquals("user", credentialFor("127.0.0.1", 1080, "socks5", Authenticator.RequestorType.SERVER)?.username)

        // Maintainer reproduction 1: an unrelated SOCKS5 endpoint never receives the pair.
        assertNull("another host must not get the pair", credentialFor("127.0.0.2", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertNull("another port must not get the pair", credentialFor("127.0.0.1", 1081, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertNull("a second SOCKS5 proxy on another port must not get the pair",
            credentialFor("192.0.2.10", 1080, "SOCKS5", Authenticator.RequestorType.PROXY))
        assertNull("missing host must not get the pair", credentialFor(null, 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertNull("blank host must not get the pair", credentialFor("  ", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))

        // Maintainer reproduction 2: HTTP proxy authentication must never receive SOCKS5 credentials.
        assertNull("HTTP proxy auth even on the same host/port",
            credentialFor("127.0.0.1", 1080, "http", Authenticator.RequestorType.PROXY))
        assertNull("SERVER + http never receives it",
            credentialFor("127.0.0.1", 1080, "http", Authenticator.RequestorType.SERVER))
        assertNull("null protocol never receives it",
            credentialFor("127.0.0.1", 1080, null, Authenticator.RequestorType.PROXY))
        assertNull("null requestor type never receives it",
            credentialFor("127.0.0.1", 1080, "SOCKS5", null))
    }

    @Test
    fun hostComparisonIsCaseInsensitiveAndCanonicalForIpLiterals() {
        NetworkProxy.configureSocksAuthentication("socks5://user:secret@Proxy.Example:1080")
        assertEquals("user", credentialFor("proxy.example", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)
        assertEquals("user", credentialFor("PROXY.EXAMPLE", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)
        assertNull(credentialFor("proxy.example.org", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))

        // The same IPv6 address can be spelled differently; compare it as bytes, not as text.
        NetworkProxy.configureSocksAuthentication("socks5://user:secret@[::1]:1080")
        assertEquals("user", credentialFor("0:0:0:0:0:0:0:1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)
        assertEquals("user", credentialFor("::1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)
        assertNull(credentialFor("::2", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
    }

    @Test
    fun switchingEndpointsInvalidatesTheOldEndpointAtomically() {
        NetworkProxy.configureSocksAuthentication("socks5://userA:passA@127.0.0.1:1080")
        assertEquals("userA", credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)

        // Switch A -> B: the old endpoint must fail closed instead of receiving the new pair.
        NetworkProxy.configureSocksAuthentication("socks5://userB:passB@127.0.0.1:1081")
        assertNull("old endpoint must not get the new pair at all",
            credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertEquals("userB", credentialFor("127.0.0.1", 1081, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)

        // Switching credentials on the same endpoint replaces the pair as a whole.
        NetworkProxy.configureSocksAuthentication("socks5://userC:passC@127.0.0.1:1081")
        val replaced = credentialFor("127.0.0.1", 1081, "SOCKS5", Authenticator.RequestorType.SERVER)
        assertEquals("userC", replaced?.username)
        assertEquals("passC", replaced?.password)
    }

    @Test
    fun disablingClearsTheBinding() {
        NetworkProxy.configureSocksAuthentication("socks5://user:secret@127.0.0.1:1080")
        assertEquals("user", credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)

        NetworkProxy.configureSocksAuthentication(null)
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))

        NetworkProxy.configureSocksAuthentication("socks5://user:secret@127.0.0.1:1080")
        NetworkProxy.configureSocksAuthentication("")
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
    }

    @Test
    fun invalidConfigurationFailsClosedAndClearsThePreviousPair() {
        NetworkProxy.configureSocksAuthentication("socks5://user:secret@127.0.0.1:1080")
        assertEquals("user", credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.username)

        // A malformed value persisted by an older build must neither throw nor keep the old pair.
        NetworkProxy.configureSocksAuthentication("not a proxy")
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))

        NetworkProxy.configureSocksAuthentication("socks5://user:secret@127.0.0.1:1080")
        NetworkProxy.configureSocksAuthentication("http://user:secret@127.0.0.1:8080") // HTTP never accepts credentials
        assertNull(credentialFor("127.0.0.1", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
    }

    @Test
    fun authBindingAndCredentialsNeverPrintTheSecret() {
        val binding = NetworkProxy.AuthBinding("127.0.0.1", 1080, NetworkProxy.Credentials("user", "secret"))
        assertFalse(binding.toString().contains("secret"))
        assertFalse(binding.toString().contains("user"))
        val parsed = NetworkProxy.credentials("socks5://user:secret@127.0.0.1:1080")
        assertEquals(parsed, binding.credentials)
        assertFalse(parsed.toString().contains("secret"))
    }

    private fun credentialFor(
        host: String?,
        port: Int,
        protocol: String?,
        type: Authenticator.RequestorType?,
    ): NetworkProxy.Credentials? = NetworkProxy.credentialFor(host, port, protocol, type)

    /**
     * The Android compile-time stub of [Authenticator] does not expose `getDefault()`, while the
     * JVM that actually runs unit tests does. Reflection keeps the `@After` restore faithful.
     */
    private fun currentDefaultAuthenticator(): Authenticator? = try {
        Authenticator::class.java.getMethod("getDefault").invoke(null) as? Authenticator
    } catch (_: Exception) {
        null
    }
}
