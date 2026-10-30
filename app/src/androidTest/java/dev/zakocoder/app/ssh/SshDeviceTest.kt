package dev.zakocoder.app.ssh

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real SSH, SFTP and tmux against a maintainer-created disposable sshd; never uses user credentials. */
class SshDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private lateinit var controller: SshController
    private lateinit var profile: SshProfile
    private var trusted = false
    private val id = "ssh-test-" + UUID.randomUUID().toString()

    @Before fun setUp() = runBlocking {
        assumeTrue("Disposable SSH fixture arguments required", arguments.containsKey("ssh_host"))
        val key = withContext(Dispatchers.IO) { File(arguments.getString("ssh_private_key_path")!!).readText() }
        profile = SshProfile(id, arguments.getString("ssh_host")!!, arguments.getString("ssh_port")!!.toInt(),
            arguments.getString("ssh_username")!!, SshAuth.PrivateKey, arguments.getString("ssh_directory")!!, true)
        controller = SshController(context)
        controller.saveProfile(profile, privateKey = key)
    }
    @After fun tearDown() = runBlocking {
        if (!::controller.isInitialized) return@runBlocking
        try {
            if (trusted) {
                try {
                    val tasks = controller.execute(id, action("tmux_list")).getJSONArray("tasks")
                    for (i in 0 until tasks.length()) controller.execute(id, action("tmux_close").put("task_id", tasks.getJSONObject(i).getString("task_id")))
                } catch (_: SshException) { /* Fixture may deliberately disconnect. */ }
                controller.execute(id, action("exec").put("command", "rm -f -- " + SshSafety.quote(id + ".txt") + " " + SshSafety.quote(id + "-large.txt") + " " + SshSafety.quote(id + "-link")))
            }
        } finally { controller.stop(); controller.removeProfile(id) }
    }
    private fun action(value: String) = JSONObject().put("action", value)
    private suspend fun trust() {
        val key = controller.probeHost(id)
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        assertEquals(id, key.profileId)
        controller.trustHost(id, key.fingerprint)
        trusted = true
    }
    private suspend fun execute(command: String) = controller.execute(id, action("exec").put("command", command))

    @Test fun unknownHostRejectedUntilExplicitTrustAndRealExecHasExitCode() = runBlocking {
        val failure = try { execute("printf should-not-run"); null } catch (error: SshException) { error }
        assertEquals("host_key_untrusted", failure?.code)
        trust()
        val status = controller.execute(id, action("status"))
        assertTrue(status.getBoolean("connected"))
        val response = execute("printf 'fixture-output'; printf 'fixture-error' >&2; exit 7")
        assertEquals("fixture-output", response.getString("stdout"))
        assertEquals("fixture-error", response.getString("stderr"))
        assertEquals(7, response.getInt("exit_code"))
        controller.forgetHost(id)
        val forgotten = try { execute("true"); null } catch (error: SshException) { error }
        assertEquals("host_key_untrusted", forgotten?.code)
        trusted = false
    }

    @Test fun exactSftpEditPreservesConcurrentChangesAndBoundsPagedHash() = runBlocking {
        trust()
        val path = "$id.txt"
        controller.execute(id, action("write_file").put("path", path).put("content", "Hello 世界\n"))
        val read = controller.execute(id, action("read_file").put("path", path))
        val sha = read.getString("sha256")
        assertEquals("Hello 世界\n", read.getString("content"))
        val firstPage = controller.execute(id, action("read_file").put("path", path).put("limit", 8))
        assertEquals("Hello ", firstPage.getString("content"))
        assertEquals(6, firstPage.getInt("next_offset_bytes"))
        val secondPage = controller.execute(id, action("read_file").put("path", path).put("offset", 6).put("limit", 4))
        assertEquals("世", secondPage.getString("content"))
        assertEquals(sha, secondPage.getString("sha256"))
        val edit = controller.execute(id, action("edit_file").put("path", path).put("expected_sha256", sha)
            .put("old_text", "Hello").put("new_text", "你好"))
        assertNotEquals(sha, edit.getString("sha256"))
        val rejected = try {
            controller.execute(id, action("write_file").put("path", path).put("content", "would overwrite").put("expected_sha256", sha)); null
        } catch (error: SshException) { error }
        assertEquals("file_changed", rejected?.code)
        assertEquals("你好 世界\n", controller.execute(id, action("read_file").put("path", path)).getString("content"))
        val large = "a".repeat(160 * 1024)
        val written = controller.execute(id, action("write_file").put("path", "$id-large.txt").put("content", large))
        val page = controller.execute(id, action("read_file").put("path", "$id-large.txt").put("offset", 100).put("limit", 1024))
        assertTrue(page.getBoolean("truncated"))
        assertEquals(1024, page.getString("content").length)
        assertEquals(written.getString("sha256"), page.getString("sha256"))
        val listing = controller.execute(id, action("list_files"))
        assertTrue(listing.getJSONArray("entries").toString().contains(path))
    }

    @Test fun symlinkAndTraversalCannotRedirectSftp() = runBlocking {
        trust()
        assertEquals(0, execute("ln -s /etc/passwd " + SshSafety.quote(id + "-link")).getInt("exit_code"))
        for (path in listOf("../etc/passwd", "$id-link")) {
            val failure = try { controller.execute(id, action("read_file").put("path", path)); null } catch (error: SshException) { error }
            assertTrue(failure?.code in setOf("unsafe_path", "invalid_path"))
        }
    }

    @Test fun outputIsBoundedAndTimeoutClosesConnection() = runBlocking {
        trust()
        val result = execute("head -c 200000 /dev/zero | tr '\\000' x")
        assertTrue(result.getBoolean("truncated"))
        assertEquals(SshSafety.MAX_BYTES, result.getString("stdout").toByteArray().size)
        val started = System.nanoTime()
        val failure = try { controller.execute(id, action("exec").put("command", "sleep 5").put("timeout_seconds", 1)); null } catch (error: SshException) { error }
        assertEquals("command_timeout", failure?.code)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
        assertFalse(controller.state.value.busy)
        assertEquals(0, execute("true").getInt("exit_code"))
    }

    @Test fun cancellationAndStopRevokeQueuedOperations() = runBlocking {
        trust()
        val running = async(Dispatchers.Default) { execute("sleep 20") }
        while (!controller.state.value.busy) delay(10)
        delay(200)
        val queuedStarted = CompletableDeferred<Unit>()
        val queued = async(Dispatchers.Default) { queuedStarted.complete(Unit); execute("printf queued-must-not-run") }
        queuedStarted.await()
        delay(100)
        val started = System.nanoTime()
        controller.stop()
        try { running.await() } catch (_: kotlinx.coroutines.CancellationException) { /* Expected. */ }
        try { queued.await() } catch (_: kotlinx.coroutines.CancellationException) { /* Expected. */ }
        assertTrue(running.isCancelled)
        assertTrue(queued.isCancelled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
        val cancelled = async(Dispatchers.Default) { execute("sleep 20") }
        while (!controller.state.value.busy) delay(10)
        delay(200)
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertFalse(controller.state.value.busy)
        assertEquals(0, execute("true").getInt("exit_code"))
    }

    @Test fun tmuxTaskSurvivesReconnectAndConfigurationSaveButForeignTaskIsRejected() = runBlocking {
        trust()
        assumeTrue(controller.execute(id, action("status")).getBoolean("tmux_available"))
        val task = controller.execute(id, action("tmux_run").put("command", "printf ready; cat"))
        assertTrue(task.getBoolean("detached"))
        val taskId = task.getString("task_id")
        controller.stop()
        val another = SshController(context)
        another.load()
        val restored = another.state.value.profiles.first { it.id == id }
        another.saveProfile(restored.copy(tmuxEnabled = true))
        assertTrue(another.execute(id, action("tmux_list")).getJSONArray("tasks").toString().contains(taskId))
        another.execute(id, action("tmux_input").put("task_id", taskId).put("text", "hello-fixture"))
        another.execute(id, action("tmux_input").put("task_id", taskId).put("key", "Enter"))
        delay(250)
        val captured = another.execute(id, action("tmux_capture").put("task_id", taskId)).getString("stdout")
        assertTrue(captured.contains("ready"))
        assertTrue(captured.contains("hello-fixture"))
        val rejected = try { another.execute(id, action("tmux_close").put("task_id", "0".repeat(32))); null } catch (error: SshException) { error }
        assertEquals("task_not_owned", rejected?.code)
        assertEquals(0, another.execute(id, action("tmux_close").put("task_id", taskId)).getInt("exit_code"))
        another.stop()
    }

    @Test fun changedConfigurationInvalidatesAnEarlierApprovalWithoutRotatingOnTmuxBookkeeping() = runBlocking {
        trust()
        val before = controller.configurationLease(id)
        assertNotNull(before)
        val current = controller.state.value.profiles.first { it.id == id }
        controller.saveProfile(current.copy(tmuxEnabled = !current.tmuxEnabled))
        assertNotEquals(before, controller.configurationLease(id))
        val rejected = try {
            controller.execute(id, action("exec").put("command", "printf approval-must-not-run"), before); null
        } catch (error: SshException) { error }
        assertEquals("configuration_changed", rejected?.code)
        assertEquals(0, execute("true").getInt("exit_code"))
    }

    @Test fun savedSecretsEncryptedAndEndpointChangeDoesNotReuseCredentialOrTrust() = runBlocking {
        trust()
        val persisted = withContext(Dispatchers.IO) { File(context.noBackupFilesDir, "ssh-connections-v1.json").readText() }
        assertFalse(persisted.contains("PRIVATE KEY"))
        assertFalse(persisted.contains(profile.username))
        controller.saveProfile(profile.copy(host = "different.invalid"))
        val changed = controller.state.value.profiles.first { it.id == id }
        assertFalse(changed.hasCredential)
        assertEquals(null, changed.hostFingerprint)
        assertThrows(SshException::class.java) { runBlocking { controller.trustHost(id, "SHA256:invalid") } }
        trusted = false
    }
}
