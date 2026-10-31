package dev.zakocoder.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual native entry sheet and shared controls; no Workbench, user storage or network. */
class MobileEntryUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(label: String, condition: () -> Boolean) = withTimeout(15000) {
        while (!condition()) { delay(50); if (!currentCoroutineContext().isActive) error(label) }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString() == label) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private fun clickable(label: String): AccessibilityNodeInfo {
        var node = requireNotNull(find(label)) { label }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(label, node.isClickable)
        return node
    }
    private suspend fun click(label: String) {
        await(label) { find(label) != null }
        assertTrue(label, clickable(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(150)
    }
    private fun bounds(label: String) = Rect().also { clickable(label).getBoundsInScreen(it) }
    private fun geometry(labels: List<String>, density: Float): String = buildString {
        append("density=$density contextDensity=${context.resources.displayMetrics.density} ")
        labels.forEach { label ->
            append("$label=[")
            var node = find(label)
            repeat(5) {
                node?.let { current ->
                    val rect = Rect().also(current::getBoundsInScreen)
                    append("${current.className}:clickable=${current.isClickable}:visible=${current.isVisibleToUser}:$rect;")
                    node = current.parent
                }
            }
            append("] ")
        }
    }
    private suspend fun stableBounds(labels: List<String>): List<Rect> {
        // Sheet semantics appear during its entrance animation, before visible bounds settle.
        var previous: List<Rect>? = null
        var stable = 0
        await("Stable visible controls: ${labels.joinToString()}") {
            val current = labels.map(::bounds)
            stable = if (current == previous && current.all { !it.isEmpty }) stable + 1 else 0
            previous = current
            stable >= 5
        }
        return requireNotNull(previous)
    }
    private suspend fun shot(name: String) {
        delay(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/mobile-kit/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 120000) fun projectChoicesRemainSeparateAndControlsHaveIndependentTouchTargetsAndExpandState() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val imported = AtomicInteger(); val created = AtomicInteger(); val cloned = AtomicInteger(); val dismissed = AtomicInteger()
        val compactClicks = AtomicInteger()
        val renderedDensity = AtomicReference<Float>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val suffix = if (dark) "dark" else "light"
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                        val density = LocalDensity.current.density
                        androidx.compose.runtime.SideEffect { renderedDensity.set(density) }
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            AddProjectSheet(true, { dismissed.incrementAndGet() }, { imported.incrementAndGet() },
                                { created.incrementAndGet() }, { cloned.incrementAndGet() })
                        }
                    }
                } }
                val importLabel = context.getString(R.string.mobile_import_source)
                val createLabel = context.getString(R.string.mobile_create_empty)
                val cloneLabel = context.getString(R.string.git_clone)
                await("three project choices") { find(importLabel) != null && find(createLabel) != null && find(cloneLabel) != null }
                val labels = listOf(importLabel, createLabel, cloneLabel)
                val rects = stableBounds(labels)
                val density = requireNotNull(renderedDensity.get())
                val diagnostic = geometry(labels, density)
                assertTrue("Project choices overlap: $diagnostic", rects.zipWithNext().all { (a, b) -> a.bottom <= b.top })
                assertTrue("Every visible project choice needs a 48dp target: $diagnostic", rects.all { it.height() >= 48 * density - 1 })
                shot("project-choices-$suffix")
                val previous = listOf(imported.get(), created.get(), cloned.get())
                click(importLabel)
                assertEquals(previous[0] + 1, imported.get()); assertEquals(previous[1], created.get()); assertEquals(previous[2], cloned.get())
                click(createLabel); assertEquals(previous[1] + 1, created.get())
                click(cloneLabel); assertEquals(previous[2] + 1, cloned.get())
                assertEquals(0, dismissed.get())

                val event = AiToolEvent("entry-test-$suffix", "call", AiTool.ReadFile, "read_file", "{path:main.py}",
                    "核对项目入口", "src/main.py", AiToolStatus.Succeeded, output = "ACTUAL_TOOL_DETAIL")
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface { Column(Modifier.width(360.dp).safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CompactAction("打开代码", { compactClicks.incrementAndGet() }, Modifier.weight(1f))
                                    CompactAction("查看终端", { compactClicks.incrementAndGet() }, Modifier.weight(1f), primary = true)
                                }
                                StatusChip("工具已就绪", ReadyGreen)
                                AiToolCard(event)
                            } }
                        }
                    }
                } }
                val tool = context.getString(R.string.ai_tool_read)
                await("tool header") { find(tool) != null }
                val controlLabels = listOf("打开代码", "查看终端", tool)
                val (left, right, header) = stableBounds(controlLabels)
                assertTrue(left.right <= right.left)
                assertTrue("Controls need 48dp targets: ${geometry(controlLabels, density)}",
                    listOf(left, right, header).all { it.height() >= 48 * density - 1 })
                assertEquals(context.getString(R.string.mobile_collapsed), AccessibilityNodeInfoCompat.wrap(clickable(tool)).stateDescription?.toString())
                assertNull(find("ACTUAL_TOOL_DETAIL"))
                click(tool)
                await("expanded output") { find("ACTUAL_TOOL_DETAIL") != null }
                assertEquals(context.getString(R.string.mobile_expanded), AccessibilityNodeInfoCompat.wrap(clickable(tool)).stateDescription?.toString())
                shot("touch-controls-$suffix")
                click(tool); await("collapsed output") { find("ACTUAL_TOOL_DETAIL") == null }
                val prior = compactClicks.get(); click("打开代码"); assertEquals(prior + 1, compactClicks.get())
                click("查看终端"); assertEquals(prior + 2, compactClicks.get())
            }
        }
    }
}
