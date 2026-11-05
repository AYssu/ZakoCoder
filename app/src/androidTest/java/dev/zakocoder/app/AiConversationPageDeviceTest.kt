package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiCall
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiToolStatus
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real navigation shell + isolated controller/files + localhost TLS; no user model or Ubuntu. */
class AiConversationPageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        // Compose can replace a subtree without invalidating UiAutomation's cached virtual nodes.
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!node.refresh()) return@buildList
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(descendants(it)) } }
        }
        return automation.windows.mapNotNull { window ->
            window.root?.takeIf { root -> root.refresh() }
        }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::descendants)
    }

    private fun find(label: String) = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString()?.lines()?.contains(label) == true ||
            it.contentDescription?.toString()?.lines()?.contains(label) == true)
    }

    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(25_000) { while (!condition()) delay(80) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError(label, error) }
    }

    private fun refreshedClickable(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        while (node != null) {
            // getParent() can return an older cached node even after the child was refreshed.
            if (!node.refresh()) return null
            if (node.isClickable) return node.takeIf { it.isVisibleToUser && it.isEnabled }
            node = node.parent
        }
        return null
    }

    private fun tapVerifiedTarget(label: String, node: AccessibilityNodeInfo) {
        assertTrue("Refresh action $label", node.refresh())
        assertTrue("Enabled visible action $label", node.isVisibleToUser && node.isEnabled && node.isClickable)
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        assertFalse("Nonempty action bounds $label", bounds.isEmpty)
        val time = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = android.view.MotionEvent.obtain(time, time + 60, android.view.MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            assertTrue("Touch down $label", instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue("Touch up $label", instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }

    private suspend fun click(label: String) {
        // The card can be above the newest response; search both directions in the actual conversation.
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(12) {
                val target = refreshedClickable(label)
                if (target != null) {
                    tapVerifiedTarget(label, target)
                    delay(250)
                    return
                }
                nodes().firstOrNull { it.isVisibleToUser && it.isScrollable && !it.isEditable }
                    ?.performAction(action)
                delay(100)
            }
        }
        throw AssertionError("Visible enabled action missing: $label")
    }

    private suspend fun screenshot(name: String) {
        delay(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/ai-conversation-page/$name.png").apply { parentFile!!.mkdirs() }
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun recordFailure(prefix: String, error: Throwable) {
        runCatching { screenshot("failure-$prefix") }
        val dump = nodes().joinToString("\n") { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            "class=${node.className} visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                "clickable=${node.isClickable} scrollable=${node.isScrollable} bounds=$bounds " +
                "text=${node.text?.take(500)} description=${node.contentDescription?.take(500)}"
        }
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/ai-conversation-page/failure-$prefix.txt").apply {
                parentFile!!.mkdirs()
                writeText("${error.javaClass.simpleName}: ${error.message}\n$dump")
            }
        }
    }

    private suspend fun showLatestResponse(prefix: String) {
        val visibleBeforeScroll = nodes().any { it.isVisibleToUser &&
            (it.text?.contains("没有执行构建") == true || it.contentDescription?.contains("没有执行构建") == true) }
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/ai-conversation-page/list-position-$prefix.txt").apply {
                parentFile!!.mkdirs()
                writeText("finalResponseVisibleBeforeExplicitScroll=$visibleBeforeScroll\n")
            }
        }
        if (!visibleBeforeScroll) screenshot("before-latest-scroll-$prefix")
        // Review assertions inspect the visible list, rather than assuming a controller send navigates it.
        val latest = context.getString(R.string.ai_latest)
        refreshedClickable(latest)?.let { target ->
            tapVerifiedTarget(latest, target)
            delay(500)
        }
        repeat(4) {
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable && !it.isEditable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(180)
        }
    }

    @Test(timeout = 180_000) fun lightActualConversationReviewAndComposer() = runBlocking { exercise(false) }
    @Test(timeout = 180_000) fun darkActualConversationReviewAndComposer() = runBlocking { exercise(true) }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private suspend fun exercise(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck") ||
            (context.packageName == "dev.zakocoder.app" &&
                InstrumentationRegistry.getArguments().getString("allowDefaultPackageFixture") == "true"))
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context, responseOnly = true) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${fixture.token}-$name", mode)
            override fun stopService(service: android.content.Intent) = false
        }
        val prefix = if (dark) "dark" else "light"
        val originalNight = shell("cmd uimode night").substringAfter(":").trim()
        check(originalNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime")) {
            "Cannot safely restore original night mode: $originalNight"
        }
        var owned: Workbench? = null
        var primaryFailure: Throwable? = null
        try {
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            withContext(Dispatchers.IO) {
                fixture.config.save(fixture.server.url("/v1").toString(), "gpt-6.1-sol", "test-only-placeholder")
            }
            withContext(Dispatchers.Main) {
                owned = Workbench(isolated, aiTransport = fixture.transport, aiConfig = fixture.config,
                    aiHistory = fixture.history, aiPermissions = AiPermissionStore(isolated))
            }
            val workbench = requireNotNull(owned)
            await("Isolated workbench loads") {
                !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading
            }
            withContext(Dispatchers.Main) { workbench.create("移动开发工作台") }
            await("Isolated project exists") { !workbench.state.value.busy && workbench.state.value.project != null }
            val projectId = requireNotNull(workbench.state.value.selectedId)
            val root = withContext(Dispatchers.IO) { workbench.projects.directory(projectId) }
            val before = "# Mobile project\n\nOld introduction.\nOld workflow.\n"
            val after = "# Mobile project\n\nDevelop on Android.\nReview every change.\n"
            withContext(Dispatchers.IO) { File(root, "README.md").writeText(before) }
            withContext(Dispatchers.Main) { workbench.ai.select(projectId) }
            await("Isolated model and permissions load") {
                workbench.ai.state.value.projectId == projectId && workbench.ai.state.value.configured &&
                    !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading
            }
            fun streamEvent(delta: JSONObject, finish: String? = null): String {
                val choice = JSONObject().put("delta", delta)
                if (finish != null) choice.put("finish_reason", finish)
                return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
            }
            val reasoningStream = buildString {
                append(streamEvent(JSONObject().put("reasoning_content", "先核对 README 的现有说明，再替换两处内容并检查差异。")))
                // Valid SSE heartbeats keep the live state visible long enough for a device screenshot.
                // Only this bounded fixture is throttled; the full response completes in a few seconds.
                repeat(8) { append(": heartbeat ${" ".repeat(64)}\n\n") }
                val readFunction = JSONObject().put("name", "read_file")
                    .put("arguments", JSONObject().put("path", "README.md").toString())
                val readCall = JSONObject().put("index", 0).put("id", "page-read")
                    .put("type", "function").put("function", readFunction)
                append(streamEvent(JSONObject().put("tool_calls", JSONArray().put(readCall))))
                append(streamEvent(JSONObject(), "tool_calls"))
                append("data: [DONE]\n\n")
            }
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/ai-conversation-page/reasoning-fixture-$prefix.sse").apply {
                    parentFile!!.mkdirs()
                    writeText(reasoningStream)
                }
            }
            fixture.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(reasoningStream).throttleBody(64, 100, TimeUnit.MILLISECONDS))
            val writeArguments = JSONObject().put("path", "README.md").put("content", after)
                .put("reason", "更新移动开发与变更审阅说明").toString()
            val writeCall = JSONObject().put("index", 0).put("id", "page-write").put("type", "function")
                .put("function", JSONObject().put("name", "write_file").put("arguments", writeArguments))
            // Hold only the next response headers so the actual read state can be captured
            // before its later approval dialog. Uploads are never throttled.
            fixture.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setHeadersDelay(4, TimeUnit.SECONDS).setBody(
                    streamEvent(JSONObject().put("tool_calls", JSONArray().put(writeCall))) +
                        streamEvent(JSONObject(), "tool_calls") + "data: [DONE]\n\n"))
            fixture.enqueue("README.md 已更新，两处说明已替换。")
            val longReply = "## 修改完成\n\n已更新 **README.md**。\n\n" +
                (1..28).joinToString("\n\n") { "第${it}项：支持在手机上开发，每次修改都可以审阅，并保留实际验证说明。" } +
                "\n\n本次只调整文档，没有执行构建。"
            val replyStream = longReply.chunked(35).joinToString("") { streamEvent(JSONObject().put("content", it)) } +
                streamEvent(JSONObject(), "stop") + "data: [DONE]\n\n"
            fixture.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(replyStream).throttleBody(240, 65, TimeUnit.MILLISECONDS))
            withContext(Dispatchers.IO) {
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                warmDeviceActivity()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent { ZakoCoderApp(workbench) }
                }
                try {
                click(context.getString(R.string.app_navigation_open))
                delay(500) // Let the drawer settle before resolving the actual destination bounds.
                val assistantTarget = requireNotNull(refreshedClickable(context.getString(R.string.nav_ai)))
                assertTrue("Select actual assistant destination", assistantTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Real assistant navigation shell is visible") {
                    find(context.getString(R.string.app_navigation_open)) != null &&
                        find(context.getString(R.string.ai_more)) != null
                }
                withContext(Dispatchers.Main) { assertTrue(workbench.ai.send("读取 README.md，把两条旧说明换成移动开发和变更审阅说明。")) }
                await("Real streamed reasoning is active") {
                    val ai = workbench.ai.state.value
                    ai.active && ai.reasoningEntryId != null && ai.timeline.any { it.reasoning.isNotBlank() }
                }
                screenshot("thinking-live-$prefix")
                await("Real file read is recorded") {
                    workbench.ai.state.value.events.any { it.tool?.wire == "read_file" && it.status == AiToolStatus.Succeeded }
                }
                assertNull("Read screenshot precedes the separate write approval", workbench.ai.state.value.approval)
                screenshot("tool-read-$prefix")
                await("Actual tool write requires approval") { workbench.ai.state.value.approval != null }
                screenshot("waiting-approval-$prefix")
                assertEquals(before, withContext(Dispatchers.IO) { File(root, "README.md").readText() })
                withContext(Dispatchers.Main) { workbench.ai.approve(true) }
                await("Actual write finishes") { !workbench.ai.state.value.active && workbench.ai.state.value.phase == AiPhase.Completed }
                assertEquals("", workbench.ai.state.value.error)
                assertEquals(after, withContext(Dispatchers.IO) { File(root, "README.md").readText() })
                val changedTurn = workbench.ai.state.value.turn
                val checkpoint = workbench.ai.checkpointList().single { it.turn == changedTurn && it.conversation == workbench.ai.state.value.conversationId }
                assertTrue(checkpoint.sealed)
                val persistedChange = workbench.ai.checkpointChanges(checkpoint.id).single { it.path == "README.md" }
                assertEquals(before, persistedChange.before)
                assertEquals(after, persistedChange.after)
                await("Composer available after actual write") { nodes().any { it.isVisibleToUser && it.isEditable && it.isEnabled } }
                screenshot("first-turn-completed-$prefix")
                val composer = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled })
                assertTrue(composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "简要说明刚才完成了什么，用 Markdown 列表回答。")
                }))
                // Use the real send action: it owns draft clearing and following the newly created turn.
                val sendTarget = requireNotNull(refreshedClickable(context.getString(R.string.ai_send)))
                assertTrue("Send without waiting for the UI to become idle", sendTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Second stream starts") { workbench.ai.state.value.turn == changedTurn + 1 }
                var streamingSamples = 0
                withTimeout(25_000) {
                    while (workbench.ai.state.value.active) {
                        assertNull("Following output never inserts a bouncing latest button",
                            find(context.getString(R.string.ai_latest)))
                        streamingSamples++
                        if (streamingSamples == 2) screenshot("streaming-controls-$prefix")
                        delay(80)
                    }
                }
                assertTrue("Observed live output frames: $streamingSamples", streamingSamples > 1)
                await("Second actual turn finishes") {
                    workbench.ai.state.value.turn == changedTurn + 1 && !workbench.ai.state.value.active &&
                        workbench.ai.state.value.phase == AiPhase.Completed
                }
                assertEquals("", workbench.ai.state.value.error)
                assertEquals(4, fixture.server.requestCount)
                val unchangedTurn = workbench.ai.state.value.turn
                val unchangedCheckpoint = workbench.ai.checkpointList().single {
                    it.turn == unchangedTurn && it.conversation == workbench.ai.state.value.conversationId
                }
                assertTrue("Read-only second turn has a sealed checkpoint", unchangedCheckpoint.sealed)
                assertTrue("Second turn does not inherit the previous write", workbench.ai.checkpointChanges(unchangedCheckpoint.id).isEmpty())
                showLatestResponse(prefix)
                await("Markdown response rendered at latest position") { nodes().any {
                    it.isVisibleToUser && (it.text?.contains("没有执行构建") == true ||
                        it.contentDescription?.contains("没有执行构建") == true)
                } }
                assertFalse("Obsolete conversation/change tab is removed", nodes().any {
                    it.isVisibleToUser && it.isClickable && it.text?.toString() == "变更"
                })
                val changeCardLabel = context.getString(R.string.ai_changes_files_count, 1)
                val visibleChangeCards = nodes().filter { node ->
                    node.isVisibleToUser && node.isClickable &&
                        (node.text?.toString()?.lines()?.contains(changeCardLabel) == true ||
                            node.contentDescription?.toString()?.lines()?.contains(changeCardLabel) == true)
                }
                assertTrue("Read-only second turn must not duplicate the previous change card", visibleChangeCards.size <= 1)
                screenshot("fullpage-completed-$prefix")
                if (InstrumentationRegistry.getArguments().getString("streamingOnly") == "true") return
                click(context.getString(R.string.ai_file_changes_title))
                await("Only the previous changed turn opens its real checkpoint sheet") {
                    find(context.getString(R.string.project_validation_turn, changedTurn)) != null && find("README.md") != null
                }
                assertNull("No change card is attributed to the read-only second turn",
                    find(context.getString(R.string.project_validation_turn, unchangedTurn)))
                await("Persisted change line counts rendered") {
                    find(context.getString(R.string.ai_file_changes_added, 2)) != null &&
                        find(context.getString(R.string.ai_file_changes_removed, 2)) != null
                }
                screenshot("changes-sheet-$prefix")
                click("README.md")
                await("Inline read-only diff exists") { nodes().any {
                    it.isVisibleToUser && it.contentDescription?.toString() == context.getString(R.string.ai_review_content)
                } }
                screenshot("inline-diff-$prefix")
                click(context.getString(R.string.close))
                click(context.getString(R.string.ai_more))
                click(context.getString(R.string.ai_checkpoints))
                await("Checkpoint manager remains reachable") { find(context.getString(R.string.ai_checkpoint_file_only)) != null }
                click(context.getString(R.string.close))
                click(context.getString(R.string.ai_more))
                click(context.getString(R.string.ai_extensions))
                await("Real extensions page opens") {
                    find(context.getString(R.string.ai_extensions_back)) != null &&
                        find(context.getString(R.string.ai_skills)) != null &&
                        find(context.getString(R.string.mcp_title)) != null
                }
                screenshot("extensions-page-$prefix")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Assistant and bottom navigation return") {
                    find(context.getString(R.string.app_navigation_open)) != null &&
                        nodes().any { it.isVisibleToUser && it.isEditable && it.isEnabled }
                }
                val draft = "下一步补上项目使用说明，暂时不要发送。"
                val editor = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled })
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                }))
                click(context.getString(R.string.ai_switch_model))
                await("Actual model sheet opens") { find(context.getString(R.string.ai_manage_models)) != null }
                screenshot("model-sheet-$prefix")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Draft survives model sheet dismissal") { nodes().any { it.isVisibleToUser && it.isEditable && it.text?.toString() == draft } }
                assertEquals("Opening or dismissing model picker makes no requests", 4, fixture.server.requestCount)
                } catch (error: Throwable) {
                    recordFailure(prefix, error)
                    throw error
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/ai-conversation-page/request-diagnostic-$prefix.txt")
                    .writeText("requests=${fixture.server.requestCount}\nphase=${owned?.ai?.state?.value?.phase}\nerror=${owned?.ai?.state?.value?.error}\n")
            }
            throw error
        } finally {
            try {
                try { withContext(Dispatchers.Main) { owned?.stopAll() } }
                finally {
                    try { withContext(Dispatchers.IO) { fixture.close() } }
                    catch (cleanup: Throwable) {
                        val original = primaryFailure
                        if (original != null) original.addSuppressed(cleanup) else throw cleanup
                    }
                }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
                shell("cmd uimode night $originalNight")
            }
        }
    }
}
