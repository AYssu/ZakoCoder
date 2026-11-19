package dev.zakocoder.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import dev.zakocoder.app.runtime.RuntimeCommand
import dev.zakocoder.app.terminal.TerminalManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real selection toolbar and production session client; needs no Ubuntu or network. */
class TerminalClipboardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var view: TerminalView
    private val context = instrumentation.targetContext
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private fun clipboardText(): String? {
        var text: String? = null
        instrumentation.runOnMainSync { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
        return text
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { label }
            SystemClock.sleep(50)
        }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.mapNotNull(::walk).firstOrNull()
            ?: instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, "ui-review/terminal-clipboard").apply { mkdirs() }
            .resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test(timeout = 60_000) fun explicitCopyWritesSelectionButOsc52CannotOverwriteClipboard() {
        warmDeviceActivity()
        val manager = TerminalManager(context) {}
        manager.prepareTypeface()
        val fixture = "copy_fixture_中文"
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    // The shell only keeps a live PTY; deterministic terminal output is fed below.
                    val item = manager.create("clipboard-fixture", "clipboard-fixture",
                        RuntimeCommand("/system/bin/sh", listOf("-c", "exec /system/bin/cat"), mapOf("PATH" to "/system/bin")))
                    view = manager.view(activity, item, 9)
                    activity.setContentView(android.widget.FrameLayout(activity).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setPadding(0, 84, 0, 84)
                        addView(view)
                    })
                    view.requestFocus()
                    clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "clipboard_before_copy"))
                }
                await("Terminal rendered") { view.width > 0 && view.height > 0 }
                instrumentation.runOnMainSync {
                    val output = "\u001b[2J\u001b[H$fixture\r\n".toByteArray()
                    view.currentSession.emulator.append(output, output.size)
                    view.onScreenUpdated()
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                        view.getPointX(3).toFloat(), 5f, 0)
                    try { view.startTextSelectionMode(event) } finally { event.recycle() }
                }
                val copy = context.getString(com.termux.view.R.string.copy_text)
                await("Copy action visible") { find(copy) != null }
                SystemClock.sleep(350) // Upstream suppresses selection dismissal in its first 300 ms.
                screenshot("selection")
                var action = checkNotNull(find(copy))
                while (!action.isClickable && action.parent != null) action = action.parent
                assertTrue("Copy click handled", action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Copy must put selected text on the clipboard") { clipboardText() == fixture }
                assertFalse("Copy exits selection", view.isSelectingText)

                // Same production session client handles escape sequences, independently of UI actions.
                instrumentation.runOnMainSync {
                    val encoded = android.util.Base64.encodeToString("program_overwrite".toByteArray(), android.util.Base64.NO_WRAP)
                    val osc = "\u001b]52;c;$encoded\u0007".toByteArray()
                    view.currentSession.emulator.append(osc, osc.size)
                    view.onScreenUpdated()
                }
                instrumentation.waitForIdleSync()
                assertEquals("OSC 52 must remain blocked", fixture, clipboardText())
                SystemClock.sleep(800) // Let the native action toolbar and clipboard overlay finish their transitions.
                screenshot("copied")
            } finally {
                instrumentation.runOnMainSync { if (::view.isInitialized) manager.detach(view) }
                runBlocking { manager.closeAll() }
                instrumentation.runOnMainSync {
                    if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("fixture", ""))
                }
            }
        }
    }
}
