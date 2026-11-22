package dev.zakocoder.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.yukonga.miuix.kmp.basic.DropdownItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Measure real Miuix popup rows, including existing icons that ignore their slot Modifier. */
@SdkSuppress(minSdkVersion = 30)
class MenuIconSpacingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun foreground() = warmDeviceActivity()

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all)
    }

    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("\n") { it.text }.orEmpty()

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { label }
            Thread.sleep(40)
        }
    }

    @Test(timeout = 60_000) fun dayIconsHaveEqualSizeAndTextGap() = verify(false)
    @Test(timeout = 60_000) fun nightIconsHaveEqualSizeAndTextGap() = verify(true)

    private fun verify(dark: Boolean) {
        val labels = listOf("Legacy terminal icon", "Modifier-aware copy icon", "Disabled history icon")
        val clicked = AtomicBoolean(false)
        val density = context.resources.displayMetrics.density
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(dark) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
                            var expanded by remember { mutableStateOf(true) }
                            WorkbenchActionMenu(expanded, { expanded = false }, listOf(
                                DropdownItem(text = labels[0], icon = {
                                    WorkbenchIcon(Glyph.Terminal, Modifier.testTag("menu-spacing-icon-0"))
                                }, onClick = { clicked.set(true) }),
                                DropdownItem(text = labels[1], icon = {
                                    WorkbenchIcon(Glyph.Copy, it.testTag("menu-spacing-icon-1"))
                                }, onClick = {}),
                                DropdownItem(text = labels[2], enabled = false, icon = {
                                    WorkbenchIcon(Glyph.History, it.testTag("menu-spacing-icon-2"))
                                }, onClick = {}),
                            ))
                        }
                    }
                }
            } }
            await("All popup rows are visible") {
                nodes { all -> labels.all { label -> all.any { text(it) == label } } }
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            val bounds = nodes { all -> labels.mapIndexed { index, label ->
                val icon = all.last { it.config.getOrNull(SemanticsProperties.TestTag) == "menu-spacing-icon-$index" }
                val title = all.last { text(it) == label }
                icon.boundsInWindow to title.boundsInWindow
            } }
            val firstTextLeft = bounds.first().second.left
            bounds.forEachIndexed { index, pair ->
                val (icon, title) = pair
                assertEquals("Icon $index width is 20dp", 20f, icon.width / density, 0.75f)
                assertEquals("Icon $index height is 20dp", 20f, icon.height / density, 0.75f)
                assertEquals("Icon $index has a 12dp text gap", 12f, (title.left - icon.right) / density, 0.75f)
                assertEquals("Every title uses the same leading column", firstTextLeft, title.left, 1f)
                assertEquals("Icon and label are vertically centered", title.center.y, icon.center.y, 1.5f * density)
            }
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val directory = File(context.cacheDir, "ui-review/menu-icon-spacing").apply { mkdirs() }
                File(directory, "${if (dark) "night" else "day"}-menu.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            nodes { all ->
                val title = all.last { text(it) == labels.first() }
                val action = generateSequence(title) { it.parent }
                    .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                assertTrue(action())
            }
            await("Action still runs after the Miuix menu finishes dismissal") {
                clicked.get() && nodes { all -> all.none { text(it) in labels } }
            }
        }
    }
}
