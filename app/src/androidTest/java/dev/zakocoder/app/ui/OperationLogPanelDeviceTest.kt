package dev.zakocoder.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Local output fixture only: no environment installation, shell process, network or user data. */
class OperationLogPanelDeviceTest {
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
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/operation-log/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun hasText(label: String) = nodes { all -> all.any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    } }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= end) throw AssertionError(label)
            Thread.sleep(50)
        }
    }
    private fun click(label: String) = nodes { all ->
        var node: SemanticsNode? = all.first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
        while (node?.config?.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node?.parent)
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun atBottom() = nodes { all -> all.any {
        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
            range.maxValue() > 0 && range.value() >= range.maxValue() - 2f
        } == true
    } }
    private fun dragToEarlierOutput() {
        val description = context.getString(R.string.operation_log_output)
        fun bounds(node: android.view.accessibility.AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && node.contentDescription?.toString() == description)
                return Rect().also(node::getBoundsInScreen)
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::bounds) }
        }
        val rect = requireNotNull(instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::bounds))
        val start = SystemClock.uptimeMillis()
        val x = rect.centerX().toFloat()
        val from = rect.top + rect.height() * .20f
        val to = rect.top + rect.height() * .80f
        for (step in 0..12) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, from + (to - from) * step / 12, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            Thread.sleep(20)
        }
    }
    @Test(timeout = 90000) fun outputFollowsUntilUserScrollsAndCanResumeAfterAppend() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        for (dark in listOf(false, true)) {
            val text = mutableStateOf((1..90).joinToString("\n") { "fixture output line $it" })
            val running = mutableStateOf(true)
            warmDeviceActivity()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            OperationLogPanel(text.value, running.value, context.getString(R.string.execution_log))
                        } }
                    }
                    }
                } }
                await("Initial output follows tail") { atBottom() }
                instrumentation.runOnMainSync { text.value += "\nfixture appended output" }
                await("Append remains at tail") { atBottom() }
                screenshot("${if (dark) "night" else "day"}-following")
                dragToEarlierOutput()
                await("User scroll pauses follow") { hasText(context.getString(R.string.operation_log_paused)) && !atBottom() }
                instrumentation.runOnMainSync { text.value += "\nfixture output while paused" }
                Thread.sleep(200)
                assertFalse("New output does not steal scroll position", atBottom())
                screenshot("${if (dark) "night" else "day"}-paused")
                click(context.getString(R.string.operation_log_latest))
                await("Latest resumes following") { atBottom() && hasText(context.getString(R.string.operation_log_following)) }
                instrumentation.runOnMainSync { running.value = false }
                click(context.getString(R.string.execution_log))
                await("Log collapses") { !hasText(context.getString(R.string.operation_log_following)) }
                click(context.getString(R.string.execution_log))
                await("Log reopens at tail") { atBottom() }
            }
        }
    }
}
