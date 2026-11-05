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
import dev.zakocoder.app.ai.liveCallDraft
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Component stream fixture. No API request, command execution or saved project modification. */
class AiToolLivePreviewDeviceTest {
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
    private fun capture(name: String) {
        Thread.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.cacheDir, "ui-live-preview").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun singleLineUpdatesAndDefaultCollapsedDetailsInBothThemes() {
        val command = mutableStateOf(liveCallDraft(0, "run_command", """{"command":"git diff && tail build.log"""))
        val file = mutableStateOf(liveCallDraft(1, "write_file", """{"path":"main.cpp","content":"#include <iostream>"""))
        val dark = mutableStateOf(false)
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark.value) {
                    MaterialTheme(colorScheme = if (dark.value) WorkbenchDarkColors else WorkbenchColors) {
                        Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            Text("实时工具预览 · 本地组件测试")
                            AiToolLivePreview(command.value)
                            AiToolLivePreview(file.value)
                        } }
                    }
                }
            } }
            await("Initial actual command preview") { text("git diff && tail build.log") }
            assertFalse("Details initially collapsed", text(context.getString(R.string.ai_tool_generating)))
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    dark.value = night
                    command.value = liveCallDraft(0, "run_command", """{"command":"git diff\ntail -n 30 build.log""")
                    file.value = liveCallDraft(1, "write_file", """{"content":"${"x".repeat(65000)}\nint main() { return 0; }","path":"main.cpp"}""")
                }
                await("Updated command and large file content") { text("tail -n 30 build.log") && text("int main() { return 0; }") }
                capture(if (night) "night-collapsed" else "day-collapsed")
            }
            nodes { all ->
                var target = all.first { it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == "tail -n 30 build.log" } == true }
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                assertTrue(target.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            await("Actual partial parameter details expanded") { text(context.getString(R.string.ai_tool_generating)) }
            capture("night-expanded")
        }
    }
}
