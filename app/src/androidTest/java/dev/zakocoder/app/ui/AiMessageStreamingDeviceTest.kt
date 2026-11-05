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
