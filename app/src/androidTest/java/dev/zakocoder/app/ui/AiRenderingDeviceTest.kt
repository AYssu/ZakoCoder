package dev.zakocoder.app.ui

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Local fixture only. Measures remount-to-first-Markdown-text, never sends a model request. */
class AiRenderingDeviceTest {
    private fun nodes(): List<SemanticsNode> = buildList {
        fun collect(node: SemanticsNode) { add(node); node.children.forEach(::collect) }
        fun visit(view: android.view.View) {
            if (view is ViewRootForTest && view.isShown) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
    }
    private fun nodeText(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test(timeout = 120000) fun markdownReentryLatency() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/dev.zakocoder.app.MainActivity"
        )).use { it.readBytes() }
        val marker = "Rendering comparison heading"
        val source = "# $marker\n\n" + (1..25).joinToString("\n\n") {
            "## Section $it\n\nA coding response with **important details** and `src/main.kt` references. ".repeat(3) +
                "\n\n```kotlin\nval count = $it\nprintln(count)\n```\n\n| Tool | Result |\n| --- | --- |\n| compiler | ready |"
        }
        var visible by mutableStateOf(false)
        fun markerVisible(): Boolean {
            val found = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                fun visitNode(n: SemanticsNode) {
                    if (n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(marker) } == true) found.set(true)
                    n.children.forEach(::visitNode)
                }
                fun visit(v: android.view.View) {
                    if (v is ViewRootForTest && v.isShown) visitNode(v.semanticsOwner.unmergedRootSemanticsNode)
                    if (v is android.view.ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) }
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            }
            return found.get()
        }
        val samples = mutableListOf<Long>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(16.dp)) {
                        if (visible) item { AiMarkdown(source) {} }
                    }
                } }
            } }
            repeat(6) {
                instrumentation.runOnMainSync { visible = false }
                instrumentation.waitForIdleSync()
                Thread.sleep(150)
                val start = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync { visible = true }
                while (!markerVisible()) {
                    check(SystemClock.elapsedRealtime() - start < 15000) { "Markdown did not render" }
                    Thread.sleep(5)
                }
                samples += SystemClock.elapsedRealtime() - start
                instrumentation.waitForIdleSync()
            }
        }
        val label = InstrumentationRegistry.getArguments().getString("renderPhase") ?: "sample"
        val report = JSONObject().put("phase", label).put("chars", source.length)
            .put("coldMs", samples.first()).put("reentryMs", JSONArray(samples.drop(1)))
        File(context.getExternalFilesDir(null), "render-$label.json").writeText(report.toString(2))
        android.util.Log.i("ZakoRenderTest", report.toString())
        assertEquals(6, samples.size)
    }

    @Test(timeout = 90000) fun planAndToolTransitionsKeepContentAndCaptureBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/dev.zakocoder.app.MainActivity"
        )).use { it.readBytes() }
        var dark by mutableStateOf(false)
        var state by mutableStateOf(dev.zakocoder.app.ai.AiState(active = true, turn = 1))
        var events by mutableStateOf(emptyList<dev.zakocoder.app.ai.AiToolEvent>())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AiTaskPlanPanel(state, 0, true)
                                androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(events.size, key = { events[it].id }) { index ->
                                        Column(Modifier.animateItem()) { AiToolCard(events[index]) }
                                    }
                                    item { AiMarkdown("## 项目检查\n\n已读取 **项目规则**，下一步执行定向验证。\n\n| 文件 | 状态 |\n| --- | --- |\n| AGENTS.md | 已读取 |") {} }
                                }
                            }
                        }
                    }
                }
            } }
            repeat(2) { theme ->
                instrumentation.runOnMainSync { dark = theme == 1; events = emptyList(); state = state.copy(plan = emptyList()) }
                Thread.sleep(1000)
                instrumentation.runOnMainSync { state = state.copy(plan = listOf(
                    dev.zakocoder.app.ai.AiPlanStep("读取项目规则", "in_progress"),
                    dev.zakocoder.app.ai.AiPlanStep("执行定向验证", "pending"))) }
                Thread.sleep(1200)
                val event = dev.zakocoder.app.ai.AiToolEvent("render-tool", "fixture", dev.zakocoder.app.ai.AiTool.ReadFile,
                    "read_file", "{}", "查看当前项目规则", "AGENTS.md", status = dev.zakocoder.app.ai.AiToolStatus.Running)
                instrumentation.runOnMainSync { events = listOf(event) }
                Thread.sleep(1200)
                instrumentation.runOnMainSync { events = listOf(event.copy(status = dev.zakocoder.app.ai.AiToolStatus.Succeeded,
                    output = "Fixture rules read", started = 1000, finished = 1010))
                    state = state.copy(plan = state.plan.mapIndexed { i, step -> step.copy(status = if (i == 0) "completed" else "in_progress") }) }
                Thread.sleep(1200)
                instrumentation.waitForIdleSync()
                val title = context.getString(dev.zakocoder.app.R.string.ai_current_plan)
                instrumentation.runOnMainSync {
                    val header = nodes().first { nodeText(it) == title }
                    val step = nodes().first { nodeText(it) == "读取项目规则" }
                    assertTrue("Plan steps must be below the header", step.boundsInRoot.top >= header.boundsInRoot.bottom)
                    assertTrue(nodes().any { nodeText(it) == "执行定向验证" })
                    var clickable = header
                    while (clickable.config.getOrNull(SemanticsActions.OnClick) == null) clickable = requireNotNull(clickable.parent)
                    assertTrue(requireNotNull(clickable.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                }
                Thread.sleep(300)
                instrumentation.runOnMainSync {
                    assertFalse("Collapsed steps must leave semantics", nodes().any { nodeText(it) == "读取项目规则" })
                    var clickable = nodes().first { nodeText(it) == title }
                    while (clickable.config.getOrNull(SemanticsActions.OnClick) == null) clickable = requireNotNull(clickable.parent)
                    assertTrue(requireNotNull(clickable.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                }
                Thread.sleep(300)
                instrumentation.runOnMainSync { assertTrue(nodes().any { nodeText(it) == "读取项目规则" }) }
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "render-${if (dark) "dark" else "light"}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                assertEquals(dev.zakocoder.app.ai.AiToolStatus.Succeeded, events.single().status)
                assertEquals("completed", state.plan.first().status)
            }
        }
    }
}
