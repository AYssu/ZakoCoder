package dev.zakocoder.app.ssh

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in disposable password-only server; never uses an existing connection. */
class SshPasswordDeviceTest {
    @Test fun passwordAuthenticationWorksAcrossRepeatedFreshConnections() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.containsKey("ssh_fixture_password_path"))
        val controller = SshController(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = "password-fixture-" + UUID.randomUUID()
        try {
            val password = File(requireNotNull(arguments.getString("ssh_fixture_password_path"))).readText()
            controller.saveProfile(SshProfile(id, "127.0.0.1", 22230, "fixture"), password = password)
            val key = controller.probeHost(id)
            controller.trustHost(id, key.fingerprint)
            repeat(5) {
                val status = controller.execute(id, JSONObject().put("action", "status"))
                assertTrue(status.getBoolean("connected"))
                val result = controller.execute(id, JSONObject().put("action", "exec").put("command", "printf fixture-ok"))
                assertEquals(0, result.getInt("exit_code"))
                assertEquals("fixture-ok\n", result.getString("stdout"))
            }
        } finally { controller.stop(); controller.removeProfile(id) }
    }
}
