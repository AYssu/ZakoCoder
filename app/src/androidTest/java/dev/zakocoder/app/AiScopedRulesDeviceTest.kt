package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real controller and disk, local TLS only. No user projects or model configuration. */
class AiScopedRulesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(block: () -> Boolean) = withTimeout(30000) { while (!block()) delay(30) }
    private fun call(id: String, name: String, vararg values: Pair<String, Any>) = AiCall(id, name,
        JSONObject().put("reason", "核对实际规则与执行边界").apply { values.forEach { put(it.first, it.second) } }.toString())
    private fun read(id: String, path: String) = call(id, "read_file", "path" to path)
    private fun write(id: String, path: String = "packages/web/a.kt") = call(id, "write_file", "path" to path, "content" to "after")
    /** Read current rule evidence, not a historical snapshot preserved for the cache prefix. */
    private fun metadata(body: String): String {
        val messages = JSONObject(body).getJSONArray("messages")
        val contents = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
        var current = contents.single { it.startsWith("User-selected skills for this task.") }
        val section = Regex("\\nSECTION ([a-z]+) \\[([0-9]+) chars\\]:\\n")
        contents.filter { it.startsWith(AI_CONTEXT_UPDATE) }.forEach { update ->
            var cursor = 0
            while (true) {
                val header = section.find(update, cursor) ?: break
                val start = header.range.last + 1
                val end = start + header.groupValues[2].toInt()
                require(end < update.length && update[end] == '\n')
                if (header.groupValues[1] == "rules") current = update.substring(start, end)
                // Skip the complete length-labelled body so rule text cannot forge section headers.
                cursor = end + 1
            }
        }
        return current
    }
    @Test fun currentRuleEvidenceFollowsLengthLabelledUpdatesAndPreservesHistoricalSnapshot() {
        val initial = "User-selected skills for this task.\nRULE_OLD"
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", initial))
        val state = AiContextCache(mapOf("rules" to "RULE_OLD"))
        val current = "RULE_NEW\nSECTION rules [8 chars]:\nFAKE_OLD"
        state.update("rules", current)
        messages.put(requireNotNull(state.nextMessage()))
        state.update("changes", "observed changes only")
        messages.put(requireNotNull(state.nextMessage()))
        assertEquals(current, metadata(JSONObject().put("messages", messages).toString()))
        state.update("rules", "Automatic loading disabled by current read permission")
        messages.put(requireNotNull(state.nextMessage()))
        val effective = metadata(JSONObject().put("messages", messages).toString())
        assertFalse(effective.contains("RULE_OLD")); assertFalse(effective.contains("RULE_NEW"))
        assertTrue(effective.contains("disabled by current read permission"))
        assertEquals(initial, messages.getJSONObject(0).getString("content"))
    }
    private val initialMetadata = mutableMapOf<String, String>()
    private fun request(f: AiDeviceTest.Fixture): String {
        val body = requireNotNull(f.server.takeRequest(3, TimeUnit.SECONDS)).body.readUtf8()
        val messages = JSONObject(body).getJSONArray("messages")
        val snapshot = (0 until messages.length()).map(messages::getJSONObject)
            .singleOrNull { it.optString("content").startsWith("User-selected skills for this task.") }?.toString()
        if (snapshot != null) assertEquals("Initial task metadata must remain byte-identical while rules update",
            initialMetadata.getOrPut(f.token) { snapshot }, snapshot)
        return body
    }
    private fun send(ai: AiController, prompt: String): Boolean {
        val accepted = ai.send(prompt)
        val state = ai.state.value
        assertTrue("Fixture send: active=${state.active}, restoring=${state.restoring}, configured=${state.configured}, " +
            "permissionsLoading=${state.permissionsLoading}, permissionsSaving=${state.permissionsSaving}, " +
            "skillsLoading=${state.skillsLoading}, historyError=${state.historyError}, " +
            "modelsLoading=${ai.models.state.value.loading}, modelsSaving=${ai.models.state.value.saving}", accepted)
        return accepted
    }
    private suspend fun policy(ai: AiController, value: AiPolicy) {
        main { ai.setPolicy(value) }; await { !ai.state.value.permissionsSaving }; assertEquals(value, ai.state.value.policy)
    }
    private suspend fun fixture(block: suspend (AiDeviceTest.Fixture, File, AiController) -> Unit) {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdir() }
            File(root, "AGENTS.md").writeText("RULE_ROOT_ACTUAL")
            File(root, "packages/web").mkdirs(); File(root, "packages/mobile").mkdirs()
            File(root, "packages/AGENTS.md").writeText("RULE_PACKAGES_ACTUAL")
            File(root, "packages/web/AGENTS.md").writeText("RULE_WEB_ACTUAL")
            File(root, "packages/web/AGENT.md").writeText("RULE_WEB_FALLBACK")
            File(root, "packages/mobile/AGENTS.md").writeText("RULE_UNVISITED_MOBILE")
            File(root, "packages/web/a.kt").writeText("before")
            val owned = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            lateinit var ai: AiController
            main { ai = AiController(owned, { root }, { _, _ -> false }, { _, _ -> error("No shell in scoped rule fixture") }, {}, {}, f.transport, f.config, history = f.history); ai.select("scoped") }
            await { ai.state.value.configured && !ai.state.value.skillsLoading && !ai.state.value.permissionsLoading &&
                !ai.models.state.value.loading && !ai.models.state.value.saving }
            try { block(f, root, ai) }
            finally { main { ai.stop() }; await { !ai.state.value.active } }
        }
    }
    @Test(timeout = 90000) fun autonomousDirectorySearchAndReadLoadOwnAncestorsWithoutSiblingRules() = runBlocking(Dispatchers.IO) {
        fixture { f, _, ai ->
            f.enqueue(calls = listOf(call("list", "list_files", "path" to "packages/web")))
            f.enqueue(calls = listOf(call("search", "search_files", "query" to "before", "path" to "packages/web", "glob" to "*.kt")))
            f.enqueue(calls = listOf(read("read", "packages/web/a.kt"))); f.enqueue("已定位实际文件。")
            main { assertTrue(send(ai, "自行查找目标代码")) }; await { !ai.state.value.active }
            assertEquals("", ai.state.value.error); assertEquals(0, ai.state.value.references.size)
            val bodies = (1..4).map { metadata(request(f)) }
            assertTrue(bodies.first().contains("RULE_ROOT_ACTUAL")); assertFalse(bodies.first().contains("RULE_WEB_ACTUAL"))
            bodies.drop(1).forEach { body ->
                assertTrue(body.contains("RULE_PACKAGES_ACTUAL")); assertTrue(body.contains("RULE_WEB_ACTUAL"))
                assertFalse(body.contains("RULE_WEB_FALLBACK")); assertFalse(body.contains("RULE_UNVISITED_MOBILE"))
                assertTrue(body.contains("scope=packages/web and descendants"))
            }
            assertEquals(listOf("AGENTS.md", "packages/AGENTS.md", "packages/web/AGENTS.md"), ai.state.value.ruleSources)
            assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Succeeded })
        }
    }
    @Test(timeout = 90000) fun sameReplyReadThenWriteWaitsForModelReceiptBeforeAnyApproval() = runBlocking(Dispatchers.IO) {
        fixture { f, root, ai ->
            f.enqueue(calls = listOf(read("read", "packages/web/a.kt"), write("early")))
            f.enqueue(calls = listOf(write("retry"))); f.enqueue("已按规则修改。")
            main { send(ai, "检查并修改") }; await { ai.state.value.approval != null }
            assertEquals("before", File(root, "packages/web/a.kt").readText())
            val early = ai.state.value.events.single { it.callId == "early" }
            assertEquals(AiToolStatus.Incomplete, early.status); assertNull(early.started)
            assertFalse(JSONObject(early.output).getBoolean("executed"))
            assertEquals("retry", ai.state.value.events.single { it.id == ai.state.value.approval!!.eventId }.callId)
            request(f); val next = request(f)
            assertTrue(metadata(next).contains("RULE_WEB_ACTUAL")); assertTrue(next.contains("project_rules_updated"))
            main { ai.approve(true) }; await { !ai.state.value.active }
            assertEquals("after", File(root, "packages/web/a.kt").readText())
            assertEquals(AiToolStatus.Succeeded, ai.state.value.events.last().status); assertEquals("", ai.state.value.error)
        }
    }
    @Test(timeout = 90000) fun directNewFileAndDirectoryAreDeferredOnceThenUseNormalApprovals() = runBlocking(Dispatchers.IO) {
        fixture { f, root, ai ->
            fun mkdir(id: String) = call(id, "make_directory", "path" to "packages/web/new-folder")
            f.enqueue(calls = listOf(write("new-early", "packages/web/new.txt"), mkdir("dir-early")))
            f.enqueue(calls = listOf(write("new-retry", "packages/web/new.txt"), mkdir("dir-retry"))); f.enqueue("已创建。")
            main { send(ai, "新增文件和目录") }; await { ai.state.value.approval != null }
            assertEquals(2, ai.state.value.events.count { it.status == AiToolStatus.Incomplete })
            assertFalse(File(root, "packages/web/new.txt").exists()); assertFalse(File(root, "packages/web/new-folder").exists())
            request(f); assertTrue(metadata(request(f)).contains("RULE_WEB_ACTUAL"))
            main { ai.approve(true) }
            await { ai.state.value.approval?.title == "packages/web/new-folder" }
            assertEquals("after", File(root, "packages/web/new.txt").readText()); assertFalse(File(root, "packages/web/new-folder").exists())
            main { ai.approve(true) }; await { !ai.state.value.active }
            assertTrue(File(root, "packages/web/new-folder").isDirectory)
            assertEquals(2, ai.state.value.events.count { it.status == AiToolStatus.Succeeded })
        }
    }
    @Test(timeout = 120000) fun approvalWaitRechecksUpdatedDeletedAndFallbackRuleVersions() = runBlocking(Dispatchers.IO) {
        fixture { f, root, ai ->
            f.enqueue(calls = listOf(read("read", "packages/web/a.kt")))
            (1..4).forEach { f.enqueue(calls = listOf(write("write-$it"))) }; f.enqueue("已按当前规则执行。")
            main { send(ai, "修改目标") }; await { ai.state.value.approval != null }
            request(f); request(f)
            File(root, "packages/web/AGENTS.md").writeText("RULE_WEB_UPDATED")
            main { ai.approve(true) }; await { ai.state.value.approval?.eventId?.let { id -> ai.state.value.events.any { it.id == id && it.callId == "write-2" } } == true }
            assertEquals("before", File(root, "packages/web/a.kt").readText())
            val updated = metadata(request(f)); assertTrue(updated.contains("RULE_WEB_UPDATED")); assertFalse(updated.contains("RULE_WEB_ACTUAL"))
            File(root, "packages/web/AGENTS.md").delete()
            main { ai.approve(true) }; await { ai.state.value.approval?.eventId?.let { id -> ai.state.value.events.any { it.id == id && it.callId == "write-3" } } == true }
            assertEquals("before", File(root, "packages/web/a.kt").readText())
            val fallback = metadata(request(f)); assertTrue(fallback.contains("RULE_WEB_FALLBACK")); assertTrue(fallback.contains("no longer active"))
            File(root, "packages/web/AGENT.md").delete()
            main { ai.approve(true) }; await { ai.state.value.approval?.eventId?.let { id -> ai.state.value.events.any { it.id == id && it.callId == "write-4" } } == true }
            assertEquals("before", File(root, "packages/web/a.kt").readText())
            val removed = metadata(request(f)); assertFalse(removed.contains("RULE_WEB_FALLBACK")); assertTrue(removed.contains("packages/web/AGENT.md")); assertTrue(removed.contains("no longer active"))
            main { ai.approve(true) }; await { !ai.state.value.active }
            assertEquals("after", File(root, "packages/web/a.kt").readText())
            assertEquals(3, ai.state.value.events.count { it.status == AiToolStatus.Incomplete })
            assertEquals("", ai.state.value.error)
        }
    }
    @Test(timeout = 120000) fun askOrDenyDoesNotImplicitlyLoadRulesAndOneApprovalOnlyReadsThatFile() = runBlocking(Dispatchers.IO) {
        for (permission in listOf(AiPermission.Ask, AiPermission.Deny)) fixture { f, root, ai ->
            policy(ai, ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + (AiTool.ReadFile to permission) + (AiTool.WriteFile to AiPermission.Allow)))
            f.enqueue(calls = listOf(call("list", "list_files", "path" to "packages/web"), read("explicit-rule", "packages/web/AGENTS.md"), write("new", "packages/web/new.txt")))
            f.enqueue("读取范围遵守权限。")
            main { send(ai, "列目录，按权限读规则后创建文件") }
            if (permission == AiPermission.Ask) { await { ai.state.value.approval != null }; main { ai.approve(true) } }
            await { !ai.state.value.active }
            val first = request(f); val second = request(f)
            for (marker in listOf("RULE_ROOT_ACTUAL", "RULE_PACKAGES_ACTUAL", "RULE_WEB_ACTUAL", "RULE_UNVISITED_MOBILE")) assertFalse(metadata(first).contains(marker))
            assertFalse(metadata(second).contains("RULE_WEB_ACTUAL"))
            assertFalse(second.contains("RULE_ROOT_ACTUAL")); assertFalse(second.contains("RULE_PACKAGES_ACTUAL")); assertFalse(second.contains("RULE_UNVISITED_MOBILE"))
            assertEquals(permission == AiPermission.Ask, second.contains("RULE_WEB_ACTUAL"))
            assertTrue(ai.state.value.ruleSources.isEmpty()); assertEquals("after", File(root, "packages/web/new.txt").readText())
            assertFalse(ai.state.value.events.any { it.status == AiToolStatus.Incomplete })
            assertEquals(if (permission == AiPermission.Ask) AiToolStatus.Succeeded else AiToolStatus.Denied, ai.state.value.events.single { it.callId == "explicit-rule" }.status)
        }
    }
    @Test(timeout = 90000) fun revokingReadsWhileWriteApprovalWaitsRemovesAutomaticRuleInjection() = runBlocking(Dispatchers.IO) {
        fixture { f, root, ai ->
            f.enqueue(calls = listOf(read("read", "packages/web/a.kt"))); f.enqueue(calls = listOf(write("write"))); f.enqueue("遵守当前读取权限。")
            main { send(ai, "修改") }; await { ai.state.value.approval != null }
            request(f); assertTrue(metadata(request(f)).contains("RULE_WEB_ACTUAL"))
            policy(ai, ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + (AiTool.ReadFile to AiPermission.Deny)))
            main { ai.approve(true) }; await { !ai.state.value.active }
            val last = metadata(request(f))
            assertTrue(last.contains("disabled by current read permission")); assertFalse(last.contains("RULE_ROOT_ACTUAL")); assertFalse(last.contains("RULE_WEB_ACTUAL"))
            assertTrue(ai.state.value.ruleSources.isEmpty()); assertEquals("after", File(root, "packages/web/a.kt").readText())
        }
    }
    @Test(timeout = 90000) fun boundedRuleWorkingSetNeedsReceiptAgainAfterEvictionAndReportsUnreadableRules() = runBlocking(Dispatchers.IO) {
        fixture { _, root, _ ->
            (0..29).forEach { n -> File(root, "part$n").mkdir(); File(root, "part$n/AGENTS.md").writeText("PART_RULE_$n\n" + "x".repeat(9000)) }
            val tracker = AiDiscoveredRules(AiTaskContext { root }, "p", emptyList())
            (0..29).forEach { n ->
                val target = "part$n/new.txt"; tracker.refresh(listOf(target)); assertTrue(tracker.needsReview(target))
                assertTrue(tracker.current.limited); assertTrue(tracker.text().length <= 36500)
                tracker.received(tracker.delivery()); assertFalse(tracker.needsReview(target))
            }
            assertFalse(tracker.current.sources.contains("part0/AGENTS.md"))
            tracker.refresh(listOf("part0/new.txt")); assertTrue(tracker.needsReview("part0/new.txt"))
            tracker.received(tracker.delivery()); assertFalse(tracker.needsReview("part0/new.txt"))
            File(root, "bad").mkdir(); File(root, "separate.txt").writeText("NEVER_FOLLOW_THIS_RULE_LINK")
            Files.createSymbolicLink(File(root, "bad/AGENTS.md").toPath(), File(root, "separate.txt").toPath())
            val unreadable = AiTaskContext { root }.rules("p", listOf("bad/new.txt"))
            assertTrue(unreadable.limited); assertTrue(unreadable.text.contains("unavailable in scope bad")); assertFalse(unreadable.text.contains("NEVER_FOLLOW_THIS_RULE_LINK"))
            assertFalse("bad" in unreadable.inspectedScopes)
        }
    }
    private fun response(text: String = "", calls: List<AiCall> = emptyList()): MockResponse {
        val delta = JSONObject().put("content", text)
        if (calls.isNotEmpty()) delta.put("tool_calls", JSONArray(calls.mapIndexed { index, c -> JSONObject().put("index", index).put("id", c.id).put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)) }))
        val body = "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n" +
            "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")))}\n\ndata: [DONE]\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }
    @Test(timeout = 90000) fun rootSearchLoadsMatchedScopeButDeniedExplorationDoesNot() = runBlocking(Dispatchers.IO) {
        fixture { f, _, ai ->
            f.enqueue(calls = listOf(call("search", "search_files", "query" to "before", "path" to "", "glob" to "*.kt")))
            f.enqueue("已定位。")
            main { send(ai, "查找目标") }; await { !ai.state.value.active }
            assertEquals("", ai.state.value.error)
            val first = metadata(request(f)); val matched = metadata(request(f))
            assertFalse(first.contains("RULE_WEB_ACTUAL")); assertTrue(matched.contains("RULE_WEB_ACTUAL"))
        }
        fixture { f, root, ai ->
            policy(ai, ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + mapOf(
                AiTool.ListFiles to AiPermission.Deny, AiTool.SearchFiles to AiPermission.Deny, AiTool.WriteFile to AiPermission.Deny)))
            f.enqueue(calls = listOf(call("list", "list_files", "path" to "packages/mobile"),
                call("search", "search_files", "query" to "anything", "path" to "packages/mobile"), write("write", "packages/mobile/new.kt")))
            f.enqueue("受限操作未执行。")
            main { send(ai, "检查禁止操作") }; await { !ai.state.value.active }
            assertEquals("", ai.state.value.error); assertFalse(File(root, "packages/mobile/new.kt").exists())
            repeat(2) { assertFalse(metadata(request(f)).contains("RULE_UNVISITED_MOBILE")) }
            assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Denied })
        }
    }
    @Test(timeout = 90000) fun twoScopesInOneReplyRequireReceiptAndKeepIdenticalBodiesIndependent() = runBlocking(Dispatchers.IO) {
        fixture { f, root, ai ->
            File(root, "packages/mobile/AGENTS.md").writeText("RULE_WEB_ACTUAL")
            policy(ai, ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + (AiTool.WriteFile to AiPermission.Allow)))
            f.enqueue(calls = listOf(read("web", "packages/web/a.kt"), read("mobile", "packages/mobile/AGENTS.md"),
                write("early-web"), write("early-mobile", "packages/mobile/new.kt")))
            f.enqueue(calls = listOf(write("web-retry"), write("mobile-retry", "packages/mobile/new.kt")))
            f.enqueue("已完成两个作用域修改。")
            main { send(ai, "检查两个目录再修改") }; await { !ai.state.value.active }
            assertEquals("", ai.state.value.error)
            assertEquals(2, ai.state.value.events.count { it.status == AiToolStatus.Incomplete })
            assertEquals("after", File(root, "packages/web/a.kt").readText()); assertEquals("after", File(root, "packages/mobile/new.kt").readText())
            request(f); val body = metadata(request(f))
            assertTrue(body.contains("packages/web/AGENTS.md")); assertTrue(body.contains("packages/mobile/AGENTS.md"))
            val tracker = AiDiscoveredRules(AiTaskContext { root }, "p", emptyList())
            tracker.refresh(listOf("packages/web/a.kt", "packages/mobile/new.kt")); tracker.received(tracker.delivery())
            File(root, "packages/mobile/AGENTS.md").writeText("RULE_MOBILE_CHANGED")
            tracker.refresh(emptyList()); assertFalse(tracker.needsReview("packages/web/a.kt")); assertTrue(tracker.needsReview("packages/mobile/new.kt"))
        }
    }
    @Test(timeout = 90000) fun fullSearchKeepsLastFocusAndDisabledDeliveryDoesNotAcknowledgeLimitedRules() = runBlocking(Dispatchers.IO) {
        fixture { _, root, _ ->
            File(root, "last").mkdir(); File(root, "last/AGENTS.md").writeText("LAST_SEARCH_RULE")
            val tracker = AiDiscoveredRules(AiTaskContext { root }, "p", emptyList())
            tracker.refresh(listOf("packages") + (1..199).map { "missing$it/a.kt" } + "last/a.kt")
            tracker.refresh(emptyList()); assertTrue(tracker.current.text.contains("LAST_SEARCH_RULE"))
            File(root, "last/AGENTS.md").delete()
            Files.createSymbolicLink(File(root, "last/AGENTS.md").toPath(), File(root, "AGENTS.md").toPath())
            tracker.refresh(listOf("last/a.kt")); tracker.clear(); tracker.received(tracker.delivery())
            tracker.refresh(listOf("last/a.kt")); assertTrue(tracker.current.limited); assertTrue(tracker.needsReview("last/a.kt"))
        }
    }
    @Test(timeout = 120000) fun discoveredRulesSurviveRealContextCompactionBeforeWriting() = runBlocking(Dispatchers.IO) {
        fixture { f, root, ai ->
            // Drive the token budget explicitly: 126k ASCII evidence exceeds half the usable
            // 80k-token window while leaving room for the real summary request and pinned rules.
            val options = AiContextOptions(contextWindowTokens = 80000, compressionThresholdPercent = 50)
            val profile = f.config.catalog().active!!
            f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
                parameters = profile.parameters, context = options, protocol = profile.protocol, systemPrompt = profile.systemPrompt))
            assertEquals(options, f.config.load().context)
            val evidence = ("q".repeat(999) + "\n").repeat(63)
            assertTrue(estimateAiTokens(evidence + evidence) > (options.contextWindowTokens - 8192L) * 50 / 100)
            for (n in 1..2) File(root, "large$n.txt").writeText(evidence)
            policy(ai, ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + (AiTool.WriteFile to AiPermission.Allow)))
            val normals = AtomicInteger(); val summaries = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.clone().readUtf8())
                    if (body.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Summarize the supplied conversation/tool JSON")) {
                        summaries.incrementAndGet(); return response("Inspect the real project, preserve current scoped rules, and verify changes.")
                    }
                    return when (normals.incrementAndGet()) {
                        1 -> response(calls = listOf(read("source", "packages/web/a.kt"), read("large1", "large1.txt")))
                        2 -> response(calls = listOf(read("large2", "large2.txt")))
                        3 -> response(calls = listOf(write("write")))
                        else -> response("完成实际文件修改，未执行构建。")
                    }
                }
            }
            main { send(ai, "探索并完成修改，保留规则") }; await { !ai.state.value.active }
            assertEquals("", ai.state.value.error); assertEquals("after", File(root, "packages/web/a.kt").readText())
            assertTrue(summaries.get() >= 1); assertEquals(summaries.get(), ai.state.value.contextCompactions)
            assertTrue(ai.state.value.requests.any { it.compaction && it.complete })
            assertTrue(ai.state.value.requests.all { it.contextWindowTokens == options.contextWindowTokens })
            val requests = (1..f.server.requestCount).map { request(f) }
            val normal = requests.filterNot { JSONObject(it).getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Summarize the supplied conversation/tool JSON") }
            assertEquals(4, normal.size)
            assertTrue(metadata(normal[2]).contains("RULE_WEB_ACTUAL")); assertFalse(metadata(normal[2]).contains("RULE_UNVISITED_MOBILE"))
            assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Succeeded })
        }
    }
}
