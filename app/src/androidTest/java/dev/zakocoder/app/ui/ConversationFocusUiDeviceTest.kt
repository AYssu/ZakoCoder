package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual saved conversation and AiPage focus, independent of user projects, model config and network. */
@SdkSuppress(minSdkVersion = 30)
class ConversationFocusUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 150_000) fun dayToolAndReasoningHitsRevealTheirActualContent() = review(false)
    @Test(timeout = 150_000) fun nightToolAndReasoningHitsRevealTheirActualContent() = review(true)

    private fun review(dark: Boolean) = runBlocking<Unit> {
        val root = File(base.cacheDir, "conversation-focus-${UUID.randomUUID()}")
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(
                "${root.name}-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        try {
            AiDeviceTest.Fixture(isolated).use { fixture ->
                val wb = withContext(Dispatchers.Main) { Workbench(isolated, aiTransport = fixture.transport,
                    aiConfig = fixture.config, aiHistory = fixture.history) }
                workbench = wb
                await("Workbench initialized") { !wb.state.value.loading }
                withContext(Dispatchers.Main) { wb.create("对话定位测试") }
                await("Fixture workspace created") { !wb.state.value.busy && wb.state.value.project != null }
                val project = requireNotNull(wb.state.value.project)
                fun tool(id: String, output: String) = AiToolEvent(id, "call-$id", AiTool.ReadFile, "read_file", "{}",
                    "检查测试文件", "$id.kt", status = AiToolStatus.Succeeded, output = output, turn = 1)
                val saved = AiState(turn = 1, phase = AiPhase.Completed, timeline = buildList {
                    add(AiEntry("user", "读取并核对两个文件", role = AiRole.User, turn = 1))
                    add(AiEntry("tool-first", toolId = "event-first", turn = 1))
                    add(AiEntry("tool-matched", toolId = "event-matched", turn = 1))
                    add(AiEntry("reasoning-matched", text = "已核对文件。", role = AiRole.Assistant,
                        reasoning = "公开内容预览。\nPUBLIC_REASONING_AUTO_REVEAL", turn = 1))
                    repeat(24) { add(AiEntry("later-$it", "后续消息 $it：保留足够滚动距离。", role = AiRole.Assistant, turn = 1)) }
                }, events = listOf(tool("event-first", "Unmatched neighboring output"),
                    tool("event-matched", "TOOL_OUTPUT_AUTO_REVEAL")))
                withContext(Dispatchers.IO) { fixture.history.save(project.id, saved) }
                withContext(Dispatchers.Main) { wb.ai.select(project.id) }
                await("Saved timeline loaded") { !wb.ai.state.value.permissionsLoading && !wb.ai.state.value.restoring &&
                    wb.ai.state.value.timeline.any { it.id == "tool-matched" } }
                val conversation = wb.ai.state.value.conversationId
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                            WorkbenchMiuixTheme(dark) {
                                val state by wb.state.collectAsState()
                                Surface(Modifier.fillMaxSize()) { AiPage(wb, state, {}, {}, {}) }
                            }
                        }
                    } }
                    await("Actual conversation visible") { has("后续消息 23：保留足够滚动距离。") }
                    withContext(Dispatchers.Main) { wb.ai.focusConversationEntry("other-workspace", conversation, "tool-matched") }
                    delay(250)
                    assertNotNull("Another workspace must not consume this request", wb.ai.conversationFocus.value)
                    withContext(Dispatchers.Main) { wb.ai.focusConversationEntry(project.id, conversation, "tool-matched") }
                    await("Tool group and the matched detail open without clicks") { has("TOOL_OUTPUT_AUTO_REVEAL") }
                    await("Matching request consumed") { wb.ai.conversationFocus.value == null }
                    assertFalse("Neighboring tool details remain collapsed", has("Unmatched neighboring output"))
                    capture(if (dark) "night-tool" else "day-tool")

                    withContext(Dispatchers.Main) { wb.ai.focusConversationEntry(project.id, conversation, "reasoning-matched") }
                    await("Public reasoning opens automatically") { has("公开内容预览。\nPUBLIC_REASONING_AUTO_REVEAL") }
                    await("Reasoning focus consumed") { wb.ai.conversationFocus.value == null }
                    capture(if (dark) "night-reasoning" else "day-reasoning")
                    click(R.string.ai_reasoning_title)
                    await("User can collapse the revealed reasoning") { !has("公开内容预览。\nPUBLIC_REASONING_AUTO_REVEAL") }
                    withContext(Dispatchers.Main) { wb.ai.focusConversationEntry(project.id, conversation, "reasoning-matched") }
                    await("A new request reopens the same item") { has("公开内容预览。\nPUBLIC_REASONING_AUTO_REVEAL") }
                    assertEquals("Navigation must not send model requests", 0, fixture.server.requestCount)
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                withContext(Dispatchers.IO) { root.deleteRecursively(); preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }

    private fun has(value: String) = nodes { all -> all.any { text(it) == value && it.boundsInWindow.height > 0 } }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private suspend fun await(label: String, ready: () -> Boolean) {
        try { withTimeout(25_000) { while (!ready()) delay(40) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }
    private fun click(id: Int) = nodes { all ->
        var node: SemanticsNode? = all.last { text(it) == base.getString(id) }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(base.cacheDir, "ui-review/conversation-focus/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
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
}
