package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.*
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Full production AiPage; isolated history/configuration and local TLS SSE, never a paid API. */
@SdkSuppress(minSdkVersion = 30)
class AiComposerUsageUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 150000) fun day360Font130CompletedAndStreaming() = review(false)
    @Test(timeout = 150000) fun night360Font130CompletedAndStreaming() = review(true)

    private fun review(dark: Boolean) = runBlocking {
        val base = context
        val own = File(base.cacheDir, "composer-usage-${UUID.randomUUID()}")
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${own.name}-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        try {
            AiDeviceTest.Fixture(isolated, timeoutSeconds = 90, responseOnly = true, readTimeoutSeconds = 60).use { fixture ->
                val model = "fixture-mobile-coder"
                withContext(Dispatchers.IO) {
                    val active = requireNotNull(fixture.config.catalog().active)
                    fixture.config.saveProfile(AiModelDraft(active.id, "本地界面测试", active.endpoint, model, null,
                        parameters = AiParameters(thinking = AiThinking.Enabled, reasoningEffort = AiReasoningEffort.High),
                        context = AiContextOptions(contextWindowTokens = 1_000_000)))
                }
                val answer = "已完成本地界面示例。\n\n仅检查对话展示，未修改项目代码。"
                fixture.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    event(JSONObject().put("content", answer)) +
                        "data: " + JSONObject().put("choices", JSONArray()).put("usage", JSONObject()
                            .put("prompt_tokens", 1_234_567).put("completion_tokens", 12_345).put("total_tokens", 1_246_912)) + "\n\n" + finish()))
                var record: AiRequestLog? = null
                val reply = fixture.transport.request(withContext(Dispatchers.IO) { fixture.config.load() },
                    JSONArray().put(JSONObject().put("role", "user").put("content", "本地界面测试")), JSONArray(),
                    observe = { record = it }) { }
                assertEquals(1_234_567L, requireNotNull(record).inputTokens)
                assertEquals(12_345L, requireNotNull(record).outputTokens)
                val wb = withContext(Dispatchers.Main) { Workbench(isolated, aiTransport = fixture.transport, aiConfig = fixture.config, aiHistory = fixture.history) }
                workbench = wb
                await("Isolated workbench") { !wb.state.value.loading }
                withContext(Dispatchers.Main) { wb.create("本地界面测试") }
                await("Isolated project") { !wb.state.value.busy && wb.state.value.project != null }
                val project = requireNotNull(wb.state.value.project)
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    fixture.history.save(project.id, AiState(turn = 1, phase = AiPhase.Completed,
                        timeline = listOf(AiEntry("usage-user", "显示本轮用量与模型设置。", role = AiRole.User, turn = 1),
                            AiEntry("usage-reply", reply.text, role = AiRole.Assistant, turn = 1)),
                        // The fixture tests number layout, not localhost's artificial throughput.
                        requests = listOf(requireNotNull(record).copy(turn = 1, round = 1,
                            elapsed = 12_000, firstEvent = 1_000)),
                        turnTimings = listOf(AiTurnTiming(1, now - 12_000, now))))
                }
                withContext(Dispatchers.Main) { wb.ai.select(project.id) }
                await("History and local model") { !wb.ai.state.value.permissionsLoading && wb.ai.state.value.timeline.size == 2 && wb.ai.state.value.configured && !wb.ai.models.state.value.loading }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                        val state by wb.state.collectAsState()
                        AiPage(wb, state, onModels = {}, onProjects = {}, onTerminal = {})
                    } } }
                    await("Completed token aggregation") { has(context.getString(R.string.ai_usage_input, "↑", "1.23M")) }
                    delay(700)
                    assertTrue(has(context.getString(R.string.ai_usage_output, "↓", "12.35K")))
                    assertTrue(has(context.getString(R.string.ai_usage_total, "Σ", "1.25M")))
                    assertFalse(has(context.getString(R.string.ai_usage_partial)))
                    assertModelRow(model)
                    capture(dark, "completed-large-usage")

                    // Start a separate conversation so a prior completed footer cannot mask a live one.
                    withContext(Dispatchers.Main) { wb.ai.conversation() }
                    await("Empty isolated conversation") { wb.ai.state.value.timeline.isEmpty() && !wb.ai.state.value.permissionsLoading }
                    val liveText = "本地流式响应。"
                    val firstEvent = event(JSONObject().put("content", liveText))
                    // Deliver one complete event now; hold completion in the next wire block.
                    val firstBlock = firstEvent + ": " + " ".repeat(180 - firstEvent.toByteArray(Charsets.UTF_8).size - 4) + "\n\n"
                    fixture.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(firstBlock + finish())
                        .throttleBody(180, 20, TimeUnit.SECONDS))
                    withContext(Dispatchers.Main) { assertTrue(wb.ai.send("开始本地流式界面测试。")) }
                    await("Actual local streaming request active") { wb.ai.state.value.active && wb.ai.state.value.requests.any { !it.complete } && wb.ai.state.value.timeline.any { it.role == AiRole.Assistant && liveText in it.text } }
                    await("Live stop action") { nodes { all -> all.any { context.getString(R.string.ai_stop) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } } }
                    assertModelRow(model)
                    nodes { all ->
                        assertFalse("Live turn must not add token statistics above the composer", all.any { node ->
                            text(node).let { it.startsWith("↑") || it.startsWith("↓") || it.startsWith("Σ") }
                        })
                    }
                    assertFalse(has(context.getString(R.string.ai_usage_partial)))
                    capture(dark, "active-streaming")
                    assertTrue("Streaming evidence must be captured before completion", wb.ai.state.value.active)
                    withContext(Dispatchers.Main) { wb.ai.stop() }
                    await("Fixture task cancelled") { !wb.ai.state.value.active }
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                withContext(Dispatchers.IO) { own.deleteRecursively(); preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }

    private fun event(delta: JSONObject) = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta))) + "\n\n"
    private fun finish() = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "stop"))) + "\n\ndata: [DONE]\n\n"
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(25000) { while (!predicate()) delay(70) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(value: String) = nodes { all -> all.any { text(it) == value } }
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }
    private fun assertModelRow(model: String) = nodes { all ->
        val name = all.single { text(it) == model }
        val thinking = all.single { text(it) == context.getString(R.string.ai_composer_thinking_effort, "high") }
        val window = all.single { text(it) == "1M" }
        listOf(name, thinking, window).forEach {
            assertTrue("Composer model metadata must be fully visible", it.boundsInWindow.width > 0 && it.boundsInWindow.height >= it.size.height * .99f)
        }
        assertTrue("Model and thinking share a row", kotlin.math.abs(name.boundsInWindow.center.y - thinking.boundsInWindow.center.y) < 15)
        assertTrue("Thinking and context share a row", kotlin.math.abs(thinking.boundsInWindow.center.y - window.boundsInWindow.center.y) < 15)
        assertTrue(all.any { it.config.getOrNull(SemanticsProperties.StateDescription) == context.getString(R.string.ai_composer_model_state, context.getString(R.string.ai_composer_thinking_effort, "high"), "1M") })
    }
    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
        val width = LocalWindowInfo.current.containerSize.width
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
            LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }
    private suspend fun capture(dark: Boolean, name: String) {
        delay(600); instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val target = File(context.cacheDir, "ui-review/composer-usage/${if (dark) "night" else "day"}-$name.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
