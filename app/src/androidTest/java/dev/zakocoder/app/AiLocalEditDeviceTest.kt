package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local TLS model fixture plus real project IO. Never reads user model settings or invokes Shell. */
class AiLocalEditDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(block: () -> Boolean) = withTimeout(25000) { while (!block()) delay(40) }
    private fun wrapper(f: AiDeviceTest.Fixture) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }
    private fun read(id: String) = AiCall(id, "read_file", """{"path":"main.txt"}""")
    private fun edit(id: String, vararg pairs: Pair<String, String>) = AiCall(id, "edit_file", JSONObject()
        .put("path", "main.txt").put("reason", "精确修改测试文件")
        .put("edits", JSONArray(pairs.map { (old, new) -> JSONObject().put("old_text", old).put("new_text", new) })).toString())
    private suspend fun ready(c: AiController) = await { c.state.value.configured && !c.state.value.permissionsLoading && !c.state.value.skillsLoading }

    @Test(timeout = 120000) fun multipleEditsRequireReviewRejectSafelyAndCanUndoApprovedChange() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }
            val target = File(root, "main.txt").apply { writeText("alpha\nbeta\n") }
            var ai: AiController? = null
            try {
                f.enqueue(calls = listOf(read("read"), edit("reject", "alpha" to "ALPHA", "beta" to "BETA")))
                f.enqueue("未获批准，保留文件。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> error("No Shell") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("edit-review")
                }
                val c = ai!!; ready(c); main { assertTrue(c.send("修改文件")) }
                await { c.state.value.approval != null }
                assertEquals("alpha\nbeta\n", target.readText())
                val review = c.state.value.approval!!
                assertTrue(review.detail.contains("-alpha")); assertTrue(review.detail.contains("+BETA"))
                main { c.approve(AiDecision.Reject) }; await { !c.state.value.active }
                assertEquals(AiToolStatus.Denied, c.state.value.events.last().status)
                assertEquals("alpha\nbeta\n", target.readText()); assertTrue(c.state.value.changes.isEmpty())
                f.enqueue(calls = listOf(read("reread"), edit("accept", "alpha" to "ALPHA", "beta" to "BETA")))
                f.enqueue("已修改，未运行构建。")
                main { assertTrue(c.send("现在允许修改")) }; await { c.state.value.approval != null }
                main { c.approve(AiDecision.Once) }; await { !c.state.value.active }
                assertEquals("ALPHA\nBETA\n", target.readText())
                val change = c.state.value.changes.single()
                assertEquals("alpha\nbeta\n", change.before!!.text); assertEquals("ALPHA\nBETA\n", change.after.text)
                assertTrue(c.checkpointList().isNotEmpty())
                main { c.undo(change) }; await { c.state.value.approval != null }
                main { c.approve(AiDecision.Once) }; await { !c.state.value.active }
                assertEquals("alpha\nbeta\n", target.readText()); assertTrue(c.state.value.changes.isEmpty())
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 120000) fun ambiguousEditsAndConcurrentChangeFailWithoutOverwritingAndHistorySurvivesRestart() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }
            val target = File(root, "main.txt").apply { writeText("alpha beta beta\n") }
            var ai: AiController? = null
            try {
                f.enqueue(calls = listOf(edit("unread", "alpha" to "ALPHA")))
                f.enqueue(calls = listOf(read("read"), edit("ambiguous", "alpha" to "ALPHA", "beta" to "BETA")))
                f.enqueue(calls = listOf(edit("stale", "alpha" to "ALPHA")))
                f.enqueue(calls = listOf(read("reread"), edit("fresh", "user" to "USER")))
                f.enqueue("保留并合并用户修改。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> error("No Shell") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("edit-conflict")
                }
                val c = ai!!; ready(c); main { assertTrue(c.send("精确修改，保留用户内容")) }
                await { c.state.value.approval != null }
                assertTrue(c.state.value.events[0].output.startsWith("[READ_REQUIRED]"))
                assertTrue(c.state.value.events[2].output.startsWith("[EDIT_AMBIGUOUS]"))
                assertEquals("alpha beta beta\n", target.readText())
                target.writeText("user beta beta\n")
                main { c.approve(AiDecision.Once) }
                await { c.state.value.approval != null && c.state.value.events.size == 6 }
                assertTrue(c.state.value.events[3].output.startsWith("[FILE_CONFLICT]"))
                assertEquals("user beta beta\n", target.readText())
                main { c.approve(AiDecision.Once) }; await { !c.state.value.active }
                assertEquals("USER beta beta\n", target.readText())
                val expected = c.state.value.events.map { it.status to it.output }
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> false }, { _, _ -> error("Never replay") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("edit-conflict")
                }
                ready(ai!!)
                assertEquals(expected, ai!!.state.value.events.map { it.status to it.output })
                assertEquals("USER beta beta\n", target.readText()); assertFalse(ai!!.state.value.active)
                assertEquals(5, f.server.requestCount)
                val checkpoint = ai!!.checkpointList().single { it.changed > 0 }
                target.writeText("later manual change\n")
                assertFalse(ai!!.restoreCheckpoint(checkpoint.id))
                assertEquals("later manual change\n", target.readText())
                target.writeText("USER beta beta\n")
                assertTrue(ai!!.restoreCheckpoint(checkpoint.id))
                assertEquals("alpha beta beta\n", target.readText())
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 90000) fun editorAndExplicitDenialPreventEditsBeforeApproval() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }
            val target = File(root, "main.txt").apply { writeText("before") }
            var ai: AiController? = null
            try {
                f.enqueue(calls = listOf(read("read"), edit("busy", "before" to "after")))
                f.enqueue("请先处理编辑器草稿。")
                main {
                    ai = AiController(wrapper(f), { root }, { _, _ -> true }, { _, _ -> error("No Shell") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("edit-denied")
                }
                val c = ai!!; ready(c); main { assertTrue(c.send("修改")) }; await { !c.state.value.active }
                assertTrue(c.state.value.events.last().output.startsWith("[EDITOR_OPEN]"))
                assertNull(c.state.value.approval); assertEquals("before", target.readText())
                main { c.setPolicy(AiPolicy(overrides = mapOf(AiTool.EditFile to AiPermission.Deny))) }
                await { !c.state.value.permissionsSaving }
                f.enqueue(calls = listOf(edit("denied", "before" to "after"))); f.enqueue("遵守拒绝。")
                main { assertTrue(c.send("再试")) }; await { !c.state.value.active }
                assertEquals(AiToolStatus.Denied, c.state.value.events.last().status)
                assertEquals("before", target.readText()); assertNull(c.state.value.approval)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test fun schemaAndParameterValidationExposeStructuredEditsOnly() {
        val tools = AiController.schemas()
        val function = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.single { it.getString("name") == "edit_file" }
        val edits = function.getJSONObject("parameters").getJSONObject("properties").getJSONObject("edits")
        assertEquals("array", edits.getString("type")); assertEquals(64, edits.getInt("maxItems"))
        val valid = JSONObject(edit("a", "old" to "new").arguments)
        assertEquals(listOf(AiTextEdit("old", "new")), AiTextEdits.parse(valid))
        for (bad in listOf("""{"path":"x","edits":[]} """, """{"path":"x","edits":[{"old_text":1,"new_text":"x"}]}""",
            """{"path":"x","edits":[{"old_text":"a","new_text":null}]}""", """{"path":"x","edits":[{"old_text":"a","new_text":"b","extra":true}]}""")) {
            try { AiTextEdits.parse(JSONObject(bad)); fail("Invalid edit") }
            catch (error: AiFileFailure) { assertEquals(AiFileFailure.Reason.INVALID_EDITS, error.reason) }
        }
    }
}
