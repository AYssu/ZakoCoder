package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.kernel.KernelPluginManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the packaged Xray binary against loopback only; never reads user nodes or credentials. */
class KernelNativeLifecycleDeviceTest {
    @Test(timeout = 60000) fun nativeSocksForwardsLocalHttpAndReplacementStopsPreviousListener() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Use the isolated verification package", context.packageName.endsWith(".runtimecheck"))
        val manager = KernelPluginManager(context)
        val service = requireNotNull(manager.bind()) { "Install the matching Lite plugin, or build Full" }
        val username = "fixture-user"
        val password = java.util.UUID.randomUUID().toString()
        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        fun start(port: Int): JSONObject {
            val config = """{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"$username","pass":"$password"}],"udp":false}}],"outbounds":[{"protocol":"freedom","settings":{}}]}"""
            return JSONObject(service.start(config, port, username, password, "lifecycle-fixture", "Local fixture"))
        }
        fun closed(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }; false
        } catch (_: java.io.IOException) { true }
        try {
            service.stop()
            val firstPort = freePort()
            assertTrue("Actual packaged executable must become SOCKS-ready", start(firstPort).getBoolean("ok"))
            assertTrue(JSONObject(service.status()).getBoolean("processAlive"))
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { http ->
                http.soTimeout = 10000
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val serving = worker.submit {
                        http.accept().use { accepted ->
                            accepted.soTimeout = 5000
                            val reader = accepted.getInputStream().bufferedReader()
                            var lines = 0
                            while (true) {
                                val line = reader.readLine() ?: error("Incomplete local HTTP request")
                                if (line.isEmpty()) break
                                check(++lines < 100)
                            }
                            accepted.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\nkernel-local".toByteArray())
                        }
                    }
                    Socket().use { socks ->
                        socks.connect(InetSocketAddress("127.0.0.1", firstPort), 3000)
                        socks.soTimeout = 10000
                        val input = DataInputStream(socks.getInputStream())
                        val output = socks.getOutputStream()
                        output.write(byteArrayOf(5, 1, 2)); output.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
                        val user = username.toByteArray(); val pass = password.toByteArray()
                        output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
                        output.write(pass.size); output.write(pass); output.flush()
                        assertEquals(1, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                        output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1,
                            (http.localPort shr 8).toByte(), http.localPort.toByte())); output.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                        input.readUnsignedByte()
                        val addressSize = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("Invalid SOCKS response") }
                        input.readFully(ByteArray(addressSize + 2))
                        output.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
                        val response = input.bufferedReader().readText()
                        assertTrue(response.startsWith("HTTP/1.1 200"))
                        assertTrue(response.endsWith("kernel-local"))
                    }
                    serving.get(10, TimeUnit.SECONDS)
                } finally { worker.shutdownNow() }
            }
            var secondPort = freePort()
            while (secondPort == firstPort) secondPort = freePort()
            assertTrue(start(secondPort).getBoolean("ok"))
            assertTrue("Replacing kernel must close previous listener", closed(firstPort))
            service.stop()
            assertTrue("Stopping kernel must close its listener", closed(secondPort))
            assertEquals("stopped", JSONObject(service.status()).getString("state"))
        } finally { try { service.stop() } finally { manager.unbind() } }
    }
}
