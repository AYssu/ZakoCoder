package dev.zakocoder.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises actual window exits; all actions are local counters, never project or model operations. */
class ModalExitDeviceTest {
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
        fun collect(node: SemanticsNode) { all += node; if (!node.config.isClearingSemantics) node.children.forEach(::collect) }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all)
    }
    private fun has(label: String) = nodes { all -> all.any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    } }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!predicate()) { check(SystemClock.uptimeMillis() < end) { label }; Thread.sleep(30) }
        instrumentation.waitForIdleSync()
    }
    private fun clickTwiceBeforeNextFrame(label: String, count: AtomicInteger) = nodes { all ->
        val target = all.last { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
        val button = generateSequence(target) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        val action = requireNotNull(button.config[SemanticsActions.OnClick].action)
        assertTrue(action()); assertTrue(action())
        assertEquals("Business action must wait for exit, even after repeated clicks", 0, count.get())
    }
    @Composable private fun Theme(dark: Boolean, identity: Any = dark, content: @Composable () -> Unit) {
        key(dark, identity) { CompositionLocalProvider(LocalWorkbenchDark provides dark) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize(), content = content)
            }
        } }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.cacheDir, "ui-review/modal-exit/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    @Test(timeout = 90_000) fun selectingFromSheetFinishesExitBeforeNavigatingInBothThemes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val calls = AtomicInteger()
                val dismissed = AtomicInteger()
                scenario.onActivity { it.setContent { Theme(dark) {
                    var open by remember(dark) { mutableStateOf(true) }
                    Text("RETAINED_PARENT")
                    if (open) WorkbenchSheet("SHEET_FIXTURE", { dismissed.incrementAndGet(); open = false }) {
                        val finish = LocalWorkbenchSheetAction.current
                        WorkbenchPrimaryButton(onClick = { finish { calls.incrementAndGet() } }) { Text("SELECT_ITEM") }
                    }
                } } }
                await("Sheet mounted") { has("SELECT_ITEM") }
                Thread.sleep(400)
                capture("sheet-${if (dark) "night" else "day"}")
                clickTwiceBeforeNextFrame("SELECT_ITEM", calls)
                await("Exit calls navigation exactly once") { calls.get() == 1 && dismissed.get() == 1 }
                assertFalse(has("SHEET_FIXTURE"))
                Thread.sleep(200)
                assertEquals(1, calls.get()); assertEquals(1, dismissed.get())
            }
        }
    }
    @Test(timeout = 90_000) fun explicitDialogActionsAnimateButBackCannotDismissRequiredApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) for (required in listOf(false, true)) {
                val calls = AtomicInteger()
                val outside = AtomicInteger()
                scenario.onActivity { it.setContent { Theme(dark, required) {
                    var open by remember(dark, required) { mutableStateOf(true) }
                    Text("RETAINED_PARENT")
                    if (open) WorkbenchAlertDialog(onDismissRequest = { outside.incrementAndGet(); open = false },
                        dismissible = !required, title = { Text("DIALOG_FIXTURE") },
                        confirmButton = { WorkbenchDialogButton(onClick = { calls.incrementAndGet(); open = false }) { Text("EXPLICIT_DECISION") } })
                } } }
                await("Dialog mounted") { has("EXPLICIT_DECISION") }
                Thread.sleep(350)
                capture("dialog-${if (dark) "night" else "day"}-${if (required) "required" else "ordinary"}")
                if (required) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    Thread.sleep(250)
                    assertTrue("Required approval stays visible after Back", has("EXPLICIT_DECISION"))
                    assertEquals(0, outside.get())
                }
                clickTwiceBeforeNextFrame("EXPLICIT_DECISION", calls)
                await("Explicit action completes after exit") { calls.get() == 1 }
                assertEquals(0, outside.get())
                assertFalse(has("DIALOG_FIXTURE"))
            }
        }
    }
}
