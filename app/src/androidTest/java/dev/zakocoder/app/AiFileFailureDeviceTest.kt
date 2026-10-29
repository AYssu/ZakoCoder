package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorFileException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real file IO and controller recovery with isolated TLS; no user's model configuration. */
class AiFileFailureDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(block: () -> Boolean) = withTimeout(25000) { while (!block()) delay(40) }
    private fun wrapper(f: AiDeviceTest.Fixture) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }
    private fun call(id: String, name: String, vararg fields: Pair<String, Any>) = AiCall(id, name,
        JSONObject().apply { fields.forEach { put(it.first, it.second) } }.toString())
    private suspend fun ready(c: AiController) = await { c.state.value.configured && !c.state.value.permissionsLoading && !c.state.value.skillsLoading }
    private fun toolOutput(request: String, callId: String): String {
        val messages = JSONObject(request).getJSONArray("messages")
        return (0 until messages.length()).map(messages::getJSONObject)
            .single { it.optString("role") == "tool" && it.optString("tool_call_id") == callId }.getString("content")
    }

    @Test(timeout = 120000) fun failedRangeDoesNotAuthorizeWriteAndConflictCanRecoverWithoutLosingProgress() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }
            val target = File(root, "main.py").apply { writeText("original\n") }
            var ai: AiController? = null; var changes = 0
            try {
                f.enqueue(calls = listOf(call("bad-range", "read_file", "path" to "main.py", "offset" to 99999)))
                f.enqueue(calls = listOf(call("unread-write", "write_file", "path" to "main.py", "content" to "must-not-write\n")))
                f.enqueue(calls = listOf(call("read", "read_file", "path" to "main.py")))
                f.enqueue(calls = listOf(call("stale-write", "write_file", "path" to "main.py", "content" to "stale replacement\n")))
                f.enqueue(calls = listOf(call("reread", "read_file", "path" to "main.py")))
                f.enqueue(calls = listOf(call("fresh-write", "write_file", "path" to "main.py", "content" to "user change\nAI addition\n")))
                f.enqueue("保留用户修改后完成；未执行构建。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> error("No command allowed by fixture") }, {}, { changes++ },
                        f.transport, f.config, history = f.history)
                    ai!!.select("file-recovery")
                }
                val c = ai!!; ready(c); main { assertTrue(c.send("保留其他修改并修复文件")) }
                await { c.state.value.approval != null }
                assertEquals("original\n", target.readText()); assertEquals(0, changes)
                assertEquals(listOf(AiToolStatus.Failed, AiToolStatus.Failed, AiToolStatus.Succeeded, AiToolStatus.Waiting), c.state.value.events.map { it.status })
                target.writeText("user change\n") // External update while first approval was pending.
                main { c.approve(AiDecision.Once) }
                await { c.state.value.approval != null && c.state.value.events.size == 6 }
                assertEquals("user change\n", target.readText()); assertEquals(0, changes)
                assertTrue(c.state.value.events[3].output.startsWith("[FILE_CONFLICT]"))
                assertTrue(c.state.value.approval!!.detail.contains("+AI addition"))
                assertFalse(c.state.value.approval!!.detail.contains("-user change"))
                main { c.approve(AiDecision.Once) }; await { !c.state.value.active }
                assertEquals("", c.state.value.error); assertEquals(1, changes)
                assertEquals("user change\nAI addition\n", target.readText())
                val requests = (1..7).map { f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8() }
                assertTrue(toolOutput(requests[1], "bad-range").startsWith("[INVALID_RANGE]"))
                assertTrue(toolOutput(requests[2], "unread-write").startsWith("[READ_REQUIRED]"))
                assertEquals("original\n", toolOutput(requests[3], "read"))
                assertTrue(toolOutput(requests[4], "stale-write").startsWith("[FILE_CONFLICT]"))
                assertEquals("user change\n", toolOutput(requests[5], "reread"))
                assertFalse(toolOutput(requests[6], "fresh-write").contains("失败"))
                val expected = c.state.value.events.map { it.status to it.output }
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> error("Never replay") }, {}, { error("Never replay") },
                        f.transport, f.config, history = f.history)
                    ai!!.select("file-recovery")
                }
                ready(ai!!)
                assertEquals(expected, ai!!.state.value.events.map { it.status to it.output })
                assertFalse(ai!!.state.value.active); assertNull(ai!!.state.value.approval)
                assertEquals(7, f.server.requestCount)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 90000) fun openEditorIsActionableAndDenyPolicyStillWinsBeforeFileInspection() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }; val target = File(root, "main.py").apply { writeText("unchanged") }
            var ai: AiController? = null
            try {
                f.enqueue(calls = listOf(call("read", "read_file", "path" to "main.py"), call("busy", "write_file", "path" to "main.py", "content" to "overwrite")))
                f.enqueue("文件在编辑器中打开，需要用户先处理草稿。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> true }, { _, _ -> error("Never bypass") }, {}, { error("No writes") },
                        f.transport, f.config, history = f.history)
                    ai!!.select("open-file")
                }
                val c = ai!!; ready(c); main { assertTrue(c.send("修改文件")) }
                await { !c.state.value.active }
                assertEquals("unchanged", target.readText()); assertNull(c.state.value.approval)
                assertTrue(c.state.value.events.last().output.startsWith("[EDITOR_OPEN]"))
                f.server.takeRequest(); assertTrue(toolOutput(f.server.takeRequest().body.readUtf8(), "busy").startsWith("[EDITOR_OPEN]"))
                main { c.setPolicy(AiPolicy(overrides = mapOf(AiTool.WriteFile to AiPermission.Deny))) }
                await { !c.state.value.permissionsSaving }
                f.enqueue(calls = listOf(call("denied", "write_file", "path" to "../.env", "content" to "never")))
                f.enqueue("遵守拒绝。")
                main { assertTrue(c.send("再试")) }; await { !c.state.value.active }
                assertEquals(AiToolStatus.Denied, c.state.value.events.last().status)
                assertEquals(context.getString(R.string.ai_permission_blocked), c.state.value.events.last().output)
                assertEquals("unchanged", target.readText()); assertNull(c.state.value.approval)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 60000) fun knownFailuresDescribeSafeRecoveryWithoutEchoingRawExceptionContents() = runBlocking(Dispatchers.IO) {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }; val files = AiProjectFiles { root }
            fun reason(expected: String, block: () -> Unit) {
                try { block(); fail("Expected $expected") } catch (error: Exception) {
                    val message = knownFileFailure(context, error)
                    assertNotNull(message); assertTrue(message!!, message.startsWith("[$expected]"))
                    assertFalse(message.contains(root.absolutePath))
                }
            }
            reason("INVALID_PATH") { files.resolve("p", "../outside") }
            reason("PROTECTED_PATH") { files.resolve("p", ".env") }
            Files.createSymbolicLink(File(root, "link").toPath(), root.toPath())
            reason("INVALID_PATH") { files.resolve("p", "link/anything") }
            reason("PARENT_MISSING") { files.write("p", "missing/new.txt", null, "hello") }
            File(root, "exists.txt").writeText("keep")
            reason("TARGET_EXISTS") { files.write("p", "exists.txt", null, "overwrite") }
            reason("FILE_UNAVAILABLE") { files.read("p", "absent.txt") }
            reason("FILE_UNSUPPORTED") { files.write("p", "nul.txt", null, "a\u0000b") }
            reason("FILE_UNSUPPORTED") { files.write("p", "bad-utf8.txt", null, "\uD800") }
            reason("FILE_LIMIT") { files.write("p", "long.txt", null, "x".repeat(20001)) }
            reason("FILE_LIMIT") { files.write("p", "large.txt", null, "x\n".repeat(530000)) }
            for (error in listOf(java.nio.file.FileAlreadyExistsException("RAW_SECRET_EXCEPTION"), java.nio.file.NoSuchFileException("RAW_SECRET_EXCEPTION"),
                EditorFileException(EditorFileException.Reason.CONFLICT), EditorFileException(EditorFileException.Reason.LIMIT), EditorFileException(EditorFileException.Reason.UNSUPPORTED))) {
                assertFalse(knownFileFailure(context, error)!!.contains("RAW_SECRET_EXCEPTION"))
            }
            assertNull(knownFileFailure(context, IllegalStateException("RAW_SECRET_EXCEPTION")))
            assertEquals("keep", File(root, "exists.txt").readText())
            assertFalse(File(root, "nul.txt").exists()); assertFalse(File(root, "bad-utf8.txt").exists())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".zakocoder-ai-") })
        }
    }
}
