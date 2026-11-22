package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the actual Agent/model/tool loop against a local TLS fixture; never contacts a real model. */
class AiAttachmentToolLoopDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun diagnostics(ai: AiController): String {
        val state = ai.state.value
        val skills = ai.globalSkills.value
        return "phase=${state.phase}, active=${state.active}, restoring=${state.restoring}, " +
            "imagesLoading=${state.imagesLoading}, permissionsLoading=${state.permissionsLoading}, permissionsSaving=${state.permissionsSaving}, " +
            "configured=${state.configured}, globalSkillsInitialized=${skills.skillsInitialized}, globalSkillsLoading=${skills.skillsLoading}, " +
            "modelLoading=${ai.models.state.value.loading}, requests=${state.requests.size}, events=${state.events.size}, " +
            "approval=${state.approval != null}, readPermission=${state.policy.permission(AiTool.ReadFile)}, " +
            "searchPermission=${state.policy.permission(AiTool.SearchFiles)}, " +
            "toolStates=${state.events.map { event -> "${event.tool}:${event.status}:${event.output.take(160).replace('\n', ' ')}" }}"
    }
    private suspend fun await(stage: String, ai: AiController, failOnApproval: Boolean = false, predicate: () -> Boolean) {
        try {
            withTimeout(45000) {
                while (!predicate()) {
                    if (failOnApproval && ai.state.value.approval != null)
                        throw AssertionError("$stage unexpectedly waits for approval: ${diagnostics(ai)}")
                    delay(20)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$stage timed out: ${diagnostics(ai)}", timeout)
        }
    }
    private fun messages(request: JSONObject): List<JSONObject> {
        val array = request.getJSONArray("messages")
        return (0 until array.length()).map(array::getJSONObject)
    }

    @Test(timeout = 120000) fun originalStaysPrivateUntilSearchAndPaginatedReadExplicitlySelectTailContent() = runBlocking {
        AiDeviceTest.Fixture(base).use { fixture ->
            val isolated = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(fixture.root, "controller-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "controller-private").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("${fixture.token}-tool-loop-$name", mode)
            }
            val projectId = "attachment-tool-loop"
            val permissions = AiPermissionStore(isolated, "attachment-tool-loop-permissions")
            withContext(Dispatchers.IO) { permissions.save(projectId, AiPolicy()) }
            val projectRoot = File(fixture.root, "project").apply { check(mkdirs()) }
            val projectSource = File(projectRoot, "main.kt").apply { writeText("fun main() = println(\"unchanged workspace\")\n") }
            val projectBytes = projectSource.readBytes()
            val sourceRoot = File(base.cacheDir, "apk-install/${fixture.token}-tool-loop").apply { check(mkdirs()) }
            val privateHead = "head-never-selected-${fixture.token}"
            val privateMiddle = "middle-never-selected-${fixture.token}"
            val query = "unique-tail-fixture-marker"
            val tail = "$query answer-only-after-read\nlast-line-closed\n"
            val content = "$privateHead\n" + "padding 你好😀\n".repeat(2000) + "$privateMiddle\n" + tail
            assertTrue(content.length > 6000)
            val tailOffset = content.indexOf(query)
            val source = File(sourceRoot, "full-original.txt").apply { writeText(content) }
            val originalBytes = source.readBytes()
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.apk-files", source)
            var controller: AiController? = null
            try {
                val ai = withContext(Dispatchers.Main) {
                    AiController(isolated, { id -> check(id == projectId); projectRoot }, { _, _ -> false },
                        { _, _ -> error("No shell expected") }, {}, {}, fixture.transport, fixture.config, history = fixture.history, permissionStore = permissions)
                        .also { controller = it; it.refreshGlobalSkills(); it.select(projectId) }
                }
                await("controller-ready", ai) { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading &&
                    !ai.models.state.value.loading && ai.globalSkills.value.skillsInitialized && !ai.globalSkills.value.skillsLoading }
                val conversationId = ai.state.value.conversationId
                assertTrue(withContext(Dispatchers.Main) { ai.addSharedFiles(listOf(uri), projectId, conversationId) })
                val attachment = ai.state.value.fileAttachments.single()
                val path = "attachment:${attachment.id}"
                assertEquals("", ai.state.value.references.single().text)
                val search = AiCall("attachment-search", "search_files", JSONObject().put("path", path)
                    .put("query", query).put("reason", "Locate the requested footer").toString())
                val read = AiCall("attachment-read", "read_file", JSONObject().put("path", path)
                    .put("offset", tailOffset).put("limit", 200).put("reason", "Read only the matching footer").toString())
                fixture.enqueue(calls = listOf(search))
                fixture.enqueue(calls = listOf(read))
                fixture.enqueue("The requested footer was found and read. No files were changed.")
                withContext(Dispatchers.Main) { assertTrue(ai.send("Find and read the footer of the attached file without changing anything.")) }
                await("agent-loop-complete", ai, failOnApproval = true) { !ai.state.value.active }
                assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
                assertFalse(ai.state.value.historyError)
                assertEquals(2, ai.state.value.events.size)
                assertEquals(listOf(AiTool.SearchFiles, AiTool.ReadFile), ai.state.value.events.map { it.tool })
                assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Succeeded })
                assertTrue(ai.state.value.changes.isEmpty())
                assertTrue(ai.state.value.commandChanges.isEmpty())
                assertNull(ai.state.value.approval)
                assertEquals(3, fixture.server.requestCount)

                val requests = (0 until 3).map {
                    JSONObject(requireNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8())
                }
                val first = requests.first().toString()
                assertTrue(first.contains(path))
                assertTrue(first.contains(attachment.displayName))
                assertFalse("The initial request must contain attachment metadata only", first.contains(privateHead))
                assertFalse(first.contains(privateMiddle))
                assertFalse(first.contains(query))
                requests.forEach { request ->
                    assertFalse("Unread head content must stay local across the whole tool loop", request.toString().contains(privateHead))
                    assertFalse("Unread middle content must stay local across the whole tool loop", request.toString().contains(privateMiddle))
                    assertFalse(request.toString().contains(content))
                }
                val secondMessages = messages(requests[1])
                val searchOutput = JSONObject(secondMessages.single { it.optString("role") == "tool" &&
                    it.optString("tool_call_id") == search.id }.getString("content"))
                assertEquals(path, searchOutput.getString("path"))
                val match = searchOutput.getJSONArray("matches").getJSONObject(0)
                assertEquals(tailOffset, match.getInt("offset"))
                assertTrue(match.getString("text").contains(query))
                val thirdMessages = messages(requests[2])
                val readOutput = JSONObject(thirdMessages.single { it.optString("role") == "tool" &&
                    it.optString("tool_call_id") == read.id }.getString("content"))
                assertEquals(path, readOutput.getString("path"))
                assertEquals(tailOffset, readOutput.getInt("offset"))
                assertEquals(tail, readOutput.getString("content"))
                assertFalse(readOutput.getBoolean("has_more"))
                assertTrue(readOutput.getBoolean("untrusted_reference"))
                assertArrayEquals(projectBytes, withContext(Dispatchers.IO) { projectSource.readBytes() })
                assertArrayEquals(originalBytes, withContext(Dispatchers.IO) { source.readBytes() })
                assertEquals(listOf("main.kt"), projectRoot.listFiles().orEmpty().map { it.name }.sorted())
                val restored = withContext(Dispatchers.IO) { fixture.history.load(projectId).state!! }
                assertEquals(listOf(attachment), restored.fileAttachments)
                assertTrue(restored.fileDraftIds.isEmpty())
                assertEquals(attachment, restored.timeline.single { it.role == AiRole.User }.inputReferences.single().attachment)
            } finally {
                withContext(Dispatchers.Main) { controller?.stop() }
                withContext(Dispatchers.IO) { sourceRoot.deleteRecursively() }
            }
        }
    }
}
