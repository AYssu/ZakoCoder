package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Component stream fixture. No API request, command execution or saved project modification. */
class AiMessageStreamingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
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
    private fun await(label: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 12000
        while (!condition()) {
            if (android.os.SystemClock.uptimeMillis() > end) throw AssertionError(label)
            Thread.sleep(40)
        }
    }
    private fun text(value: String) = nodes { all -> all.any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == value } == true
    } }
    private fun copies() = nodes { all -> all.count {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.ai_copy_response)) == true
    } }
    private fun capture(night: Boolean, phase: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(context.cacheDir, "ui-review/task-actions/${if (night) "night" else "day"}-$phase.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun toolRoundsKeepOneTaskWithoutIntermediateReplyActionRows() {
        val state = mutableStateOf(AiState())
        val dark = mutableStateOf(false)
        val progress = AiEntry("progress", "我先读取项目配置，再检查入口。", role = AiRole.Assistant, turn = 1)
        val second = progress.copy(id = "progress-two", text = "入口已找到，继续检查运行命令。")
        val answer = progress.copy(id = "answer", text = "检查完成，运行命令已确认。")
        val read = AiToolEvent("read", "read", AiTool.ReadFile, AiTool.ReadFile.wire,
            "{}", "", "package.json", AiToolStatus.Succeeded, turn = 1)
        val search = read.copy(id = "search", callId = "search", tool = AiTool.SearchFiles,
            name = AiTool.SearchFiles.wire, target = "src/main.ts", status = AiToolStatus.Running)
        fun update(entries: List<AiEntry>, events: List<AiToolEvent>, active: Boolean) {
            instrumentation.runOnMainSync { state.value = AiState(turn = 1, active = active, timeline = entries, events = events) }
        }
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark.value) {
                    WorkbenchMiuixTheme(dark.value) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                                val current = state.value
                                val display = aiDisplayActivity(current, emptySet())
                                val actionIds = display.answerActionIds(current)
                                display.entries.forEach { entry -> key(entry.id) {
                                    val group = display.tools[entry.id]
                                    if (group != null) AiReadActivity(group, active = current.active)
                                    else AiMessage(entry, null, {}, {}, showActions = entry.id in actionIds,
                                        onRequests = {}, actions = { Text("变更摘要") })
                                } }
                            }
                        }
                    }
                }
            } }
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync { dark.value = night }
                val entries = listOf(progress, AiEntry("read", toolId = "read", turn = 1),
                    second, AiEntry("search", toolId = "search", turn = 1))
                update(entries, listOf(read, search), true)
                await("Both progress messages remain visible") { text(progress.text) && text(second.text) }
                Thread.sleep(500)
                assertEquals("A finished request in an active task has no reply footer", 0, copies())
                assertFalse(text("变更摘要"))
                capture(night, "tool-rounds")
                update(entries + answer, listOf(read, search.copy(status = AiToolStatus.Succeeded)), true)
                await("Final response is visible before task completion") { text(answer.text) }
                assertEquals("No actions before task completion", 0, copies())
                update(entries + answer, listOf(read, search.copy(status = AiToolStatus.Succeeded)), false)
                await("Only the final answer gains a copy action") { copies() == 1 && text("变更摘要") }
                Thread.sleep(400) // Capture after the tool disclosure's exit transition settles.
                capture(night, "completed")
                update(entries, listOf(read, search.copy(status = AiToolStatus.Failed)), false)
                await("No stale actions after restoring a tool-only stopped task") { copies() == 0 }
                assertTrue(text(progress.text))
                assertTrue(text(second.text))
            }
        }
    }

    @Test fun actionRowWaitsForStreamAndPresentationToFinish() {
        val source = mutableStateOf("开始输出")
        val streaming = mutableStateOf(true)
        val dark = mutableStateOf(false)
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark.value) {
                    MaterialTheme(colorScheme = if (dark.value) WorkbenchDarkColors else WorkbenchColors) {
                        Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            AiMessage(AiEntry("stream-controls", source.value, role = AiRole.Assistant), null, {}, {},
                                responseStreaming = streaming.value, onRequests = {}, actions = { Text("变更摘要") })
                        } }
                    }
                }
            } }
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync { dark.value = night; streaming.value = true }
                for (chunk in 1..12) {
                    instrumentation.runOnMainSync { source.value += " 第${chunk}段流式回复" }
                    Thread.sleep(40)
                    assertFalse("Copy stays absent while streaming", nodes { all -> all.any {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.ai_copy_response)) == true
                    } })
                    assertFalse("Summary stays absent while streaming", text("变更摘要"))
                }
                instrumentation.runOnMainSync { streaming.value = false }
                await("Final actions appear after presentation catches up") { text("变更摘要") }
                assertEquals("One stable copy action", 1, nodes { all -> all.count {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.ai_copy_response)) == true
                } })
                assertTrue("More is still available", nodes { all -> all.any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.ai_message_actions)) == true
                } })
            }
        }
    }
}
