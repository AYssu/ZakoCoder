package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Local TLS, isolated stores and fixture executor; never contacts a commercial provider. */
class AiChangeEvidenceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(30) }
    private fun metadata(body: String): String {
        val messages = JSONObject(body).getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
            .first { it.startsWith("User-selected skills for this task.") }
    }
    private fun evidence(body: String) = JSONObject(metadata(body).lineSequence().first { it.startsWith("{\"current_task_changes\":") }).getJSONObject("current_task_changes")
    private fun paths(evidence: JSONObject): Set<String> = evidence.getJSONArray("paths").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("path") }.toSet() }
    private fun wrapper(f: AiDeviceTest.Fixture) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }

    @Test(timeout = 90000) fun subsequentRequestsReceiveNetChangesAndRevertedPathDisappears() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdirs() }
            File(root, "a.txt").writeText("original-a"); File(root, "b.txt").writeText("original-b")
            var ai: AiController? = null
            try {
                f.enqueue(calls = listOf(AiCall("patch", "run_command", "{\"command\":\"fixture-patch\",\"reason\":\"Edit fixture\"}")))
                f.enqueue(text = "Fixture long progress " + "x".repeat(100000), calls = listOf(AiCall("revert", "run_command", "{\"command\":\"fixture-revert\",\"reason\":\"Revert one file\"}")))
                f.enqueue("Compressed fixture progress, current changes must come from fresh metadata.")
                f.enqueue("完成，变更记录不等于验证通过。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, command ->
                        when (command) {
                            "fixture-patch" -> { File(root, "a.txt").writeText("changed-a"); File(root, "b.txt").writeText("changed-b"); File(root, "c.txt").writeText("BODY_NOT_FOR_METADATA") }
                            "fixture-revert" -> File(root, "a.txt").writeText("original-a")
                            else -> error("Unexpected command")
                        }
                        CommandResult(0, "fixture executor finished")
                    }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("changes")
                }
                val c = ai!!
                await { c.state.value.configured && !c.state.value.permissionsLoading && !c.state.value.skillsLoading }
                main { c.setPolicy(AiPolicy(AiPermission.Allow, emptyMap())) }
                await { c.state.value.policy.permission(AiTool.RunCommand) == AiPermission.Allow }
                main { assertTrue(c.send("修改后报告实际变更")) }; await { !c.state.value.active }
                assertEquals(AiPhase.Completed, c.state.value.phase)
                val requests = (1..4).map { f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8() }
                assertEquals(0, evidence(requests[0]).getInt("observed_changed_items"))
                val changed = evidence(requests[1]); assertEquals(setOf("a.txt", "b.txt", "c.txt"), paths(changed))
                assertEquals(3, changed.getInt("observed_changed_items")); assertEquals(0, changed.getInt("omitted_items"))
                assertFalse(changed.getBoolean("sealed")); assertFalse(changed.getBoolean("limited")); assertTrue(changed.getLong("sampled_at_ms") > 0)
                assertFalse(changed.toString().contains("BODY_NOT_FOR_METADATA"))
                assertEquals(setOf("b.txt", "c.txt"), paths(evidence(requests[3])))
                assertTrue(changed.getString("scope").contains("not proof of AI authorship"))
                assertEquals(1, c.state.value.contextCompactions)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 90000) fun askAndDenyReadPermissionsDisableAutomaticChangePaths() = runBlocking {
        for (permission in listOf(AiPermission.Ask, AiPermission.Deny)) AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdirs() }; File(root, "private-path.txt").writeText("before")
            var ai: AiController? = null
            try {
                f.enqueue(calls = listOf(AiCall("patch", "run_command", "{\"command\":\"fixture-change\",\"reason\":\"Fixture\"}")))
                f.enqueue("未自动读取变更清单。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> File(root, "private-path.txt").writeText("after"); CommandResult(0, "done") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("restricted")
                }
                val c = ai!!; await { c.state.value.configured && !c.state.value.permissionsLoading && !c.state.value.skillsLoading }
                main { c.setPolicy(AiPolicy(AiPermission.Deny, mapOf(AiTool.ReadFile to permission, AiTool.RunCommand to AiPermission.Allow))) }
                await { c.state.value.policy.permission(AiTool.ReadFile) == permission && c.state.value.policy.permission(AiTool.RunCommand) == AiPermission.Allow }
                main { assertTrue(c.send("执行已授权的夹具操作")) }; await { !c.state.value.active }
                assertEquals(AiPhase.Completed, c.state.value.phase)
                repeat(2) {
                    val data = metadata(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertTrue(data.contains("Automatic task change evidence disabled")); assertFalse(data.contains("current_task_changes")); assertFalse(data.contains("private-path.txt"))
                }
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 60000) fun metadataIsBoundedLegacyAwareAndDoesNotReadBlobs() = runBlocking(Dispatchers.IO) {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdirs() }; val store = File(f.root, "proof")
            val cp = AiCheckpoints(store) { root }
            File(root, "mode.txt").writeText("unchanged-body")
            val key = cp.begin("p", "conversation", 7)
            Files.setPosixFilePermissions(File(root, "mode.txt").toPath(), PosixFilePermissions.fromString("rwx------"))
            repeat(40) { File(root, "added-$it.txt").writeText("secret-body-$it") }
            File(root, "node_modules").mkdir(); File(root, "node_modules/ignored.txt").writeText("dependency")
            cp.update("p", key)
            val dir = store.listFiles()!!.single().resolve(key)
            // Metadata stays readable even without content blobs; it must not become another source-body reader.
            dir.listFiles()!!.filter { Regex("[a-f0-9]{64}").matches(it.name) }.forEach { assertTrue(it.delete()) }
            val text = cp.changeContext("p", key); val data = JSONObject(text).getJSONObject("current_task_changes")
            assertEquals(41, data.getInt("observed_changed_items")); assertEquals(32, data.getJSONArray("paths").length()); assertEquals(9, data.getInt("omitted_items"))
            assertTrue(text.length < 12000); assertFalse(text.contains("secret-body")); assertFalse(text.contains("ignored.txt"))
            val mode = data.getJSONArray("paths").let { a -> (0 until a.length()).map(a::getJSONObject).single { it.getString("path") == "mode.txt" } }
            assertFalse(mode.getBoolean("content_changed")); assertTrue(mode.getBoolean("mode_changed"))
            val manifestFile = File(dir, "manifest.json"); val manifest = JSONObject(manifestFile.readText())
            manifest.remove("sampledAt"); manifest.put("limited", true)
            manifest.getJSONArray("entries").getJSONObject(0).put("after", JSONObject().put("hash", "unknown").put("mode", "unknown"))
            manifestFile.writeText(manifest.toString())
            val legacy = JSONObject(cp.changeContext("p", key)).getJSONObject("current_task_changes")
            assertTrue(legacy.isNull("sampled_at_ms")); assertTrue(legacy.getBoolean("limited"))
            val unknown = legacy.getJSONArray("paths").getJSONObject(0)
            assertEquals("unknown", unknown.getString("kind")); assertTrue(unknown.isNull("content_changed"))
        }
    }

    @Test(timeout = 60000) fun longPathsHitCharacterBudgetWithoutInventingTruncatedPaths() = runBlocking(Dispatchers.IO) {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdirs() }; val cp = AiCheckpoints(File(f.root, "proof")) { root }
            val key = cp.begin("p", "conversation", 1)
            var parent = root
            repeat(4) { n -> parent = File(parent, "$n" + "a".repeat(220)).apply { mkdir() } }
            repeat(35) { File(parent, "file-$it.txt").writeText("bounded") }
            cp.update("p", key)
            val text = cp.changeContext("p", key); val data = JSONObject(text).getJSONObject("current_task_changes")
            val items = data.getJSONArray("paths")
            assertEquals(39, data.getInt("observed_changed_items")); assertTrue(items.length() in 1..31)
            assertTrue((0 until items.length()).sumOf { items.getJSONObject(it).toString().length } <= 8000)
            assertEquals(39 - items.length(), data.getInt("omitted_items")); assertTrue(text.length < 12000)
            repeat(items.length()) { assertTrue(File(root, items.getJSONObject(it).getString("path")).exists()) }
        }
    }

    @Test(timeout = 90000) fun unreadableManifestReplacesPreviouslySentEvidenceWithUnknown() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdirs() }; File(root, "observed.txt").writeText("before")
            var ai: AiController? = null; var requestCount = 0; var manifest: File? = null; var original: String? = null
            fun reply(call: AiCall?): MockResponse {
                val delta = JSONObject().put("content", "Fixture progress")
                if (call != null) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", call.id)
                    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))))
                val choice = JSONObject().put("delta", delta).put("finish_reason", if (call == null) "stop" else "tool_calls")
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\ndata: [DONE]\n\n")
            }
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    return when (++requestCount) {
                        1 -> reply(AiCall("patch", "run_command", "{\"command\":\"fixture-change\",\"reason\":\"Fixture\"}"))
                        2 -> {
                            assertEquals(setOf("observed.txt"), paths(evidence(body)))
                            manifest = f.history.checkpointDirectory.walkTopDown().single { it.name == "manifest.json" }
                            original = manifest!!.readText(); manifest!!.writeText("invalid manifest")
                            reply(AiCall("inspect", "list_files", "{\"path\":\"\",\"reason\":\"Inspect\"}"))
                        }
                        3 -> {
                            val data = metadata(body)
                            assertTrue(data.contains("Current task change evidence unavailable")); assertFalse(data.contains("current_task_changes"))
                            manifest!!.writeText(original!!)
                            reply(null)
                        }
                        else -> error("Unexpected request")
                    }
                }
            }
            try {
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> File(root, "observed.txt").writeText("after"); CommandResult(0, "done") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("unavailable")
                }
                val c = ai!!; await { c.state.value.configured && !c.state.value.permissionsLoading && !c.state.value.skillsLoading }
                main { c.setPolicy(AiPolicy(AiPermission.Allow, emptyMap())) }
                await { c.state.value.policy.permission(AiTool.RunCommand) == AiPermission.Allow }
                main { assertTrue(c.send("操作后检查")) }; await { !c.state.value.active }
                assertEquals(AiPhase.Completed, c.state.value.phase); assertEquals(3, requestCount)
            } finally {
                if (manifest != null && original != null) manifest!!.writeText(original!!)
                main { ai?.stop() }; await { ai?.state?.value?.active != true }
            }
        }
    }

}
