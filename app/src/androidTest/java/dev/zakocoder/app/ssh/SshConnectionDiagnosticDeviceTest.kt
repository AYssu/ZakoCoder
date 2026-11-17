package dev.zakocoder.app.ssh

import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Disposable loopback endpoints; no user server, stored connection or credential is used. */
class SshConnectionDiagnosticDeviceTest {
    @Test fun refusedTcpConnectionReportsNetworkFailure() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        probe(port, "tcp_failed")
    }

    @Test fun acceptedTcpWithoutSshReportsHandshakeFailure() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.soTimeout = 10000
            val worker = Executors.newSingleThreadExecutor()
            try {
                val accepted = worker.submit {
                    server.accept().use { socket ->
                        socket.getOutputStream().write("SSH-2.0-diagnostic-fixture\r\n".toByteArray())
                        socket.getOutputStream().flush()
                        // Close before key exchange. This is not an authentication rejection.
                    }
                }
                probe(server.localPort, "handshake_failed")
                accepted.get(10, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    private suspend fun probe(port: Int, expected: String) {
        val controller = SshController(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = "diagnostic-" + UUID.randomUUID()
        try {
            controller.saveProfile(SshProfile(id, "127.0.0.1", port, "diagnostic-fixture"))
            val failure = try { controller.probeHost(id); null } catch (error: SshException) { error }
            assertEquals(expected, failure?.code)
            assertEquals(expected, controller.state.value.error)
        } finally { controller.stop(); controller.removeProfile(id) }
    }
}
