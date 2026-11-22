package dev.zakocoder.app.ui

import android.content.ClipboardManager
import android.content.ClipData
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiEntry
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual message gestures and clipboard, with isolated fixture text and no model or device grants. */
@SdkSuppress(minSdkVersion = 30)
class MessageCopyPopupDeviceTest {
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

    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("\n") { it.text }.orEmpty()
    private fun shown(value: String) = nodes { all -> all.any { text(it) == value } }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { label }
            Thread.sleep(40)
        }
    }

    private fun tap(label: String) = nodes { all ->
        val item = all.last { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        val action = generateSequence(item) { it.parent }
            .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(action())
    }

    private fun press(value: String, long: Boolean) {
        val point = nodes { all -> all.last { text(it) == value }.boundsInWindow.center }
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

    private fun clip(): String? = main {
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun capture(dark: Boolean, stage: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.cacheDir, "ui-review/message-copy").apply { mkdirs() }
            File(directory, "${if (dark) "night" else "day"}-$stage.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 90_000) fun dayMessageActionsCopyAndWaitForExit() = messageActions(false)
    @Test(timeout = 90_000) fun nightMessageActionsCopyAndWaitForExit() = messageActions(true)

    private fun messageActions(dark: Boolean) {
        val message = "检查当前改动，保留已有文件与测试。"
        val copied = context.getString(R.string.ai_copy_user)
        val edit = context.getString(R.string.ai_message_edit)
        val callback = AtomicReference(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(dark) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            AiMessage(AiEntry("message-copy-actions", message, role = AiRole.User),
                                null, {}, {}, onEdit = { callback.set(true) }, onRetry = {}, onRequests = {}, onQuote = {})
                        }
                    }
                }
            } }
            await("Message ready") { shown(message) }
            assertFalse("Menu is not permanently visible", shown(copied))
            press(message, false)
            await("Native Miuix message menu") { shown(edit) }
            capture(dark, "actions")
            main { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "fixture-before-copy")) }
            tap(copied)
            await("Copy follows menu exit and restores the page clipboard") { !shown(copied) && clip() == message }
            scenario.onActivity { assertTrue("Message page regains focus", it.hasWindowFocus()) }
            press(message, false)
            await("Message menu opens again") { shown(edit) }
            tap(edit)
            await("Edit runs after menu closes") { callback.get() && !shown(edit) }
            capture(dark, "dismissed")
        }
    }

    /** Long press selects real text; it must not become the tap-to-open message menu. */
    @Test(timeout = 90_000) fun longPressCopiesUserAndAssistantText() {
        val user = "CopyFixtureUser"
        val assistant = "CopyFixtureAssistant"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(false) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            AiMessage(AiEntry("message-copy-user", user, role = AiRole.User), null, {}, {}, onQuote = {})
                            AiMessage(AiEntry("message-copy-assistant", assistant, role = AiRole.Assistant), null, {}, {})
                        }
                    }
                }
            } }
            await("Both text roles ready") { shown(user) && shown(assistant) }
            for (value in listOf(user, assistant)) {
                main { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "fixture-before-selection")) }
                press(value, true)
                assertFalse("Long press must not open message actions", shown(context.getString(R.string.ai_quote_message)))
                val copyLabel = context.getString(android.R.string.copy)
                fun copyNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    // Android's compact ActionMode often renders Copy as an icon only.
                    // Accessibility exposes that title through contentDescription, not text.
                    if (node.text?.toString() == copyLabel || node.contentDescription?.toString() == copyLabel) {
                        return generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    }
                    repeat(node.childCount) { child -> copyNode(node.getChild(child))?.let { return it } }
                    return null
                }
                try {
                    capture(false, "long-press-${if (value == user) "user" else "assistant"}")
                    await("Native text selection Copy available for $value") {
                        val action = instrumentation.uiAutomation.windows.firstNotNullOfOrNull { copyNode(it.root) }
                            ?: copyNode(instrumentation.uiAutomation.rootInActiveWindow) ?: return@await false
                        action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } catch (failure: Throwable) {
                    capture(false, "long-press-failure")
                    val labels = mutableListOf<String>()
                    fun collect(node: AccessibilityNodeInfo?) {
                        if (node == null || labels.size >= 150) return
                        labels += "text=${node.text};description=${node.contentDescription};clickable=${node.isClickable}"
                        repeat(node.childCount) { collect(node.getChild(it)) }
                    }
                    collect(instrumentation.uiAutomation.rootInActiveWindow)
                    throw AssertionError("${failure.message}; accessibility=$labels; semantics=" +
                        nodes { all -> all.map { text(it) }.filter(String::isNotBlank) }, failure)
                }
                await("Selected text reaches actual clipboard: $value") { clip() == value }
            }
            capture(false, "long-press-copied")
        }
    }
}
