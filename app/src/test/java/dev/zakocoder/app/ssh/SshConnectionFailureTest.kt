package dev.zakocoder.app.ssh

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import org.junit.Assert.assertEquals
import org.junit.Test

class SshConnectionFailureTest {
    @Test fun authenticationDiagnosticsExposeOnlyKnownProtocolMethods() {
        assertEquals("publickey,password", SshConnectionFailure.authenticationMethods(
            IllegalStateException(Exception("Auth fail for methods 'publickey,password'"))))
        assertEquals("keyboard-interactive", SshConnectionFailure.authenticationMethods(
            Exception("Auth fail for methods 'keyboard-interactive,private-server-text'")))
        assertEquals("unknown", SshConnectionFailure.authenticationMethods(Exception("secret-password")))
        assertEquals("unknown", SshConnectionFailure.authenticationMethods(Exception("Auth fail for methods 'secret'")))
    }

    @Test fun wrappedNetworkAndCryptoErrorsKeepTheirCause() {
        listOf(
            UnknownHostException("private-host") to "dns_failed",
            ConnectException("private-ip") to "tcp_failed",
            SocketTimeoutException("secret") to "connection_timeout",
            NoSuchAlgorithmException("provider-error") to "crypto_unavailable",
        ).forEach { (cause, expected) ->
            assertEquals(expected, SshConnectionFailure.code(IllegalStateException("private-key", cause), "ssh_handshake"))
        }
    }

    @Test fun authAndHandshakeFailuresStayDistinctWithoutExposingRawMessages() {
        assertEquals("authentication_failed", SshConnectionFailure.code(Exception("Auth fail for private-user"), "authentication"))
        assertEquals("handshake_failed", SshConnectionFailure.code(Exception("remote-controlled-text"), "ssh_handshake"))
        assertEquals("connection_or_auth_failed", SshConnectionFailure.code(Exception("unknown"), "authentication"))
    }

    @Test fun algorithmNegotiationHasAnExplicitCode() {
        assertEquals("algorithm_negotiation_failed", SshConnectionFailure.code(
            com.jcraft.jsch.JSchAlgoNegoFailException::class.java
                .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(0, "fixture-client", "fixture-server"), "ssh_handshake"))
    }

    @Test fun cyclicCauseIsBounded() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertEquals("handshake_failed", SshConnectionFailure.code(first, "ssh_handshake"))
    }

}
