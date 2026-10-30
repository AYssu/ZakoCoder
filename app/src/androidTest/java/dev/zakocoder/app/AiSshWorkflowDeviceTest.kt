package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.ssh.SshAuth
import dev.zakocoder.app.ssh.SshController
import dev.zakocoder.app.ssh.SshProfile
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local HTTPS model fixture → real SSH/SFTP. Uses only a disposable server and synthetic key. */
class AiSshWorkflowDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(10) }
    private fun action(value: String) = JSONObject().put("action", value)
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun toolOutput(body: JSONObject, call: String): JSONObject {
        val messages = body.getJSONArray("messages")
        val message = (0 until messages.length()).map(messages::getJSONObject)
            .single { it.optString("role") == "tool" && it.optString("tool_call_id") == call }
        return JSONObject(message.getString("content"))
    }

    @Test(timeout = 180000) fun approvedAiExecWritesRemoteFileAndNextSftpReadReturnsActualContentAndHash() = runBlocking {
        assumeTrue("Disposable SSH fixture arguments required", arguments.containsKey("ssh_host"))
        val id = "ssh-ai-" + UUID.randomUUID().toString()
        val path = "$id.txt"
        val content = "AI SSH real workflow verified\n"
        val remote = SshController(base)
        var trusted = false
        var ai: AiController? = null
        AiDeviceTest.Fixture(base, timeoutSeconds = 45, readTimeoutSeconds = 30).use { f ->
            try {
                val key = withContext(Dispatchers.IO) { File(requireNotNull(arguments.getString("ssh_private_key_path"))).readText() }
                remote.saveProfile(SshProfile(id, requireNotNull(arguments.getString("ssh_host")),
                    requireNotNull(arguments.getString("ssh_port")).toInt(), requireNotNull(arguments.getString("ssh_username")),
                    SshAuth.PrivateKey, requireNotNull(arguments.getString("ssh_directory"))), privateKey = key)
                val host = remote.probeHost(id)
                remote.trustHost(id, host.fingerprint)
                trusted = true
                val isolated = object : ContextWrapper(base) {
                    override fun getFilesDir() = File(f.root, "controller-files").apply { mkdirs() }
                    override fun getNoBackupFilesDir() = File(f.root, "controller-private").apply { mkdirs() }
                }
                main {
                    ai = AiController(isolated, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("No phone command expected") }, {}, {}, f.transport, f.config, history = f.history,
                        capabilitySnapshot = { owner -> remote.configurationLease(owner)?.let { mapOf(CapabilityPackage.Ssh to "package|$it") } ?: emptyMap() },
                        sshDescription = { "Disposable SSH fixture" },
                        sshTool = { owner, args, approved ->
                            check(owner == id && approved != null)
                            remote.execute(owner, args, expectedConfiguration = approved.substringAfter('|'))
                        })
                    ai!!.select(id)
                }
                val controller = requireNotNull(ai)
                await { controller.state.value.configured && !controller.state.value.permissionsLoading &&
                    !controller.state.value.skillsLoading && !controller.models.state.value.loading }
                main { controller.setPolicy(AiPolicy(AiPermission.Allow, emptyMap())) }
                await { !controller.state.value.permissionsSaving }
                f.enqueue(calls = listOf(AiCall("remote-write", "ssh_remote", action("exec")
                    .put("command", "printf '%s\\n' 'AI SSH real workflow verified' > '$path'; printf written")
                    .put("reason", "Create the disposable remote verification file").toString())))
                f.enqueue(calls = listOf(AiCall("remote-read", "ssh_remote", action("read_file").put("path", path)
                    .put("reason", "Verify the actual remote file and its hash").toString())))
                f.enqueue("Remote content and SHA-256 were verified from the tool results.")
                main { assertTrue(controller.send("Create and verify the disposable remote file")) }
                await { controller.state.value.approval != null }
                assertEquals(AiToolStatus.Waiting, controller.state.value.events.single().status)
                val before = remote.execute(id, action("exec").put("command", "test ! -e '$path'"))
                assertEquals("Nothing may be written before approval", 0, before.getInt("exit_code"))
                main { controller.approve(AiDecision.TaskTool) }
                await { !controller.state.value.active }
                assertEquals("", controller.state.value.error)
                assertEquals(AiPhase.Completed, controller.state.value.phase)
                assertEquals(2, controller.state.value.events.size)
                assertTrue(controller.state.value.events.all { it.status == AiToolStatus.Succeeded })
                val requests = (1..3).map { JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()) }
                val executed = toolOutput(requests[1], "remote-write")
                assertEquals(0, executed.getInt("exit_code")); assertEquals("written", executed.getString("stdout"))
                val read = toolOutput(requests[2], "remote-read")
                assertEquals(content, read.getString("content")); assertEquals(sha(content), read.getString("sha256"))
                assertEquals(content, remote.execute(id, action("read_file").put("path", path)).getString("content"))
                assertTrue(f.history.load(id).state!!.events.all { it.status == AiToolStatus.Succeeded })
            } finally {
                main { ai?.stop() }; await { ai?.state?.value?.active != true }
                try {
                    if (trusted) remote.execute(id, action("exec").put("command", "rm -f -- '$path'"))
                } finally { remote.stop(); remote.removeProfile(id) }
            }
        }
    }
}
