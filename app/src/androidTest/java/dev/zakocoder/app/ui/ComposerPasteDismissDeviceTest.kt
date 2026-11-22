package dev.zakocoder.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real native Paste toolbar, physical outside taps and no saved account or project fixture. */
@SdkSuppress(minSdkVersion = 30)
class ComposerPasteDismissDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var accessibilityFlags = 0

    @Before fun foreground() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        accessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        warmDeviceActivity()
    }

    @After fun restoreAccessibility() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = accessibilityFlags
        automation.serviceInfo = info
    }

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

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            assertTrue(label, SystemClock.uptimeMillis() < end)
            Thread.sleep(40)
        }
    }

    private fun field() = nodes { all -> all.first { it.config.getOrNull(SemanticsActions.SetText) != null } }
    private fun focused() = field().config.getOrNull(SemanticsProperties.Focused) == true

    private fun press(point: androidx.compose.ui.geometry.Offset, long: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, point.x, point.y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
        if (long) Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 250)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.x, point.y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun pasteAction(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = context.getString(android.R.string.paste)
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
            return generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        }
        repeat(node.childCount) { pasteAction(node.getChild(it))?.let { action -> return action } }
        return null
    }

    private fun nativePaste() = instrumentation.uiAutomation.windows.firstNotNullOfOrNull { pasteAction(it.root) }
        ?: pasteAction(instrumentation.uiAutomation.rootInActiveWindow)

    private fun capture(dark: Boolean, stage: String) {
        instrumentation.waitForIdleSync()
        // Accessibility reports the native ActionMode before its compositor animation ends.
        Thread.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.cacheDir, "ui-review/composer-paste").apply { mkdirs() }
            File(directory, "${if (dark) "night" else "day"}-$stage.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 120_000) fun nativePasteDismissesOutsideAndStillPastesInBothThemes() {
        val draft = mutableStateOf("")
        val dark = mutableStateOf(false)
        val outsideClicks = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(dark.value) {
                    val dismiss = rememberComposerFocusDismissState()
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.fillMaxSize().dismissComposerFocusOnOutsidePress(dismiss).padding(16.dp)) {
                            WorkbenchTextButton(onClick = { outsideClicks.incrementAndGet() },
                                modifier = Modifier.testTag("paste-outside-button")) { Text("Outside action fixture") }
                            Box(Modifier.weight(1f).fillMaxWidth().testTag("paste-outside-space"))
                            AiComposerDock(draft.value, { draft.value = it }, true, false, true, false, true,
                                {}, {}, focusDismissState = dismiss, configuration = { Spacer(Modifier.weight(1f)) })
                        }
                    }
                }
            } }
            await("Composer mounted") { nodes { all -> all.any { it.config.getOrNull(SemanticsActions.SetText) != null } } }
            for (theme in listOf(false, true)) {
                main {
                    dark.value = theme
                    draft.value = ""
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("", "PasteFixture"))
                }
                fun openPaste() {
                    press(field().boundsInWindow.center)
                    press(field().boundsInWindow.center, long = true)
                    await("Native Paste toolbar visible") { nativePaste() != null }
                    assertTrue("Composer owns focus before outside touch", focused())
                }
                openPaste()
                capture(theme, "open")
                press(nodes { all -> all.first { it.config.getOrNull(SemanticsProperties.TestTag) == "paste-outside-space" }.boundsInWindow.center })
                await("Whitespace tap dismisses Paste and composer focus") { !focused() && nativePaste() == null }
                assertEquals("Outside tap retains draft", "", draft.value)
                capture(theme, "dismissed")

                openPaste()
                val expectedClicks = outsideClicks.get() + 1
                press(nodes { all -> all.first { it.config.getOrNull(SemanticsProperties.TestTag) == "paste-outside-button" }.boundsInWindow.center })
                await("Outside control both dismisses and receives its click") {
                    !focused() && nativePaste() == null && outsideClicks.get() == expectedClicks
                }

                openPaste()
                assertTrue("Native Paste stays operational", requireNotNull(nativePaste()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Native Paste inserts clipboard text") { draft.value == "PasteFixture" }
                capture(theme, "pasted")
            }
        }
    }
}
