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
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real state transitions with synthetic tool events; no command or model request. */
class LiveToolTransitionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun readsKeepTheirTargetsVisibleUntilTheTaskEnds() {
        warmDeviceActivity()
        val state = mutableStateOf(AiState())
        val dark = mutableStateOf(false)
        fun event(id: String, status: AiToolStatus) = AiToolEvent(id, id, AiTool.ReadFile,
            AiTool.ReadFile.wire, "{}", "", "src/$id.kt", status, turn = 1)
        fun update(events: List<AiToolEvent>, active: Boolean = true) {
            instrumentation.runOnMainSync {
                state.value = AiState(active = active, turn = 1, events = events,
                    timeline = events.map { AiEntry(it.id, toolId = it.id, turn = 1) })
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark.value) {
                    WorkbenchMiuixTheme(dark.value) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                                val display = aiDisplayActivity(state.value, emptySet())
                                display.entries.forEach { entry -> key(entry.id) {
                                    val group = display.tools[entry.id]
                                    if (group != null) AiReadActivity(group, active = state.value.active)
                                    else state.value.events.firstOrNull { it.id == entry.toolId }?.let { AiToolCard(it) }
                                } }
                            }
                        }
                    }
                }
            } }
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync { dark.value = night }
                update(listOf(event("first", AiToolStatus.Running)))
                Thread.sleep(1000)
                assertTrue("Live read target must be visible", hasText("src/first.kt"))
                capture(night, "first-running")
                update(listOf(event("first", AiToolStatus.Succeeded), event("second", AiToolStatus.Running)))
                Thread.sleep(60)
                assertTrue("Completed read must remain visible while the next read starts", hasText("src/first.kt"))
                assertTrue("Next read is inside the same expanded group", hasText("src/second.kt"))
                capture(night, "second-entering")
                Thread.sleep(850)
                capture(night, "second-running")
                update(listOf(event("first", AiToolStatus.Succeeded), event("second", AiToolStatus.Succeeded)), active = false)
                Thread.sleep(400)
                assertFalse("Details collapse once the task ends", hasText("src/first.kt"))
                capture(night, "completed")
            }
        }
    }
    private fun hasText(text: String): Boolean {
        val found = AtomicReference(false)
        instrumentation.runOnMainSync {
            fun collect(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) found.set(true)
                node.children.forEach(::collect)
            }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return found.get()
    }
    private fun capture(dark: Boolean, phase: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(instrumentation.targetContext.cacheDir, "ui-review/process-transition/${if (dark) "night" else "day"}-$phase.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
