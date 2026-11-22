package dev.zakocoder.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real pointer long presses and the native toolbar; no synthetic CopyText action. */
class AiMarkdownSelectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var accessibilityFlags = 0

    @Before fun enableNativeToolbarWindows() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        accessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    @After fun restoreAccessibilityFlags() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = accessibilityFlags
        automation.serviceInfo = info
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val answer = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            answer.set(block(all))
        }
        return answer.get()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= until) {
                capture("failure")
                val details = StringBuilder()
                fun dump(node: AccessibilityNodeInfo?, depth: Int) {
                    if (node == null || depth > 20) return
                    details.appendLine("${" ".repeat(depth)}${node.className} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable}")
                    repeat(node.childCount) { dump(node.getChild(it), depth + 1) }
                }
                instrumentation.uiAutomation.windows.forEach { dump(it.root, 0) }
                dump(instrumentation.uiAutomation.rootInActiveWindow, 0)
                File(context.cacheDir, "ui-review/markdown-selection/failure-windows.txt").writeText(details.toString())
                throw AssertionError(label)
            }
            Thread.sleep(40)
        }
    }

    private fun bodyContains(value: String) = nodes { all -> all.any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { value in it.text } == true
    } }

    private fun pointInWord(word: String, occurrence: Int = 0): Offset = nodes { all ->
        val node = all.filter { candidate ->
            candidate.config.contains(SemanticsActions.GetTextLayoutResult) &&
                candidate.config.getOrNull(SemanticsProperties.Text)?.any { word in it.text } == true
        }[occurrence]
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        val layout = results.single()
        val start = layout.layoutInput.text.text.indexOf(word)
        val box = layout.getBoundingBox(start + word.length / 2)
        Offset(node.boundsInWindow.left + box.center.x, node.boundsInWindow.top + box.center.y)
    }

    private fun longPress(word: String, occurrence: Int = 0) {
        instrumentation.runOnMainSync {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("selection fixture", "UNSELECTED_FIXTURE"))
        }
        pressWord(word, occurrence, ViewConfiguration.getLongPressTimeout().toLong() + 250)
    }

    private fun pressWord(word: String, occurrence: Int = 0, holdMillis: Long = 80) {
        val point = pointInWord(word, occurrence)
        val downAt = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            try { instrumentation.sendPointerSync(event) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        try { Thread.sleep(holdMillis) }
        finally { inject(MotionEvent.ACTION_UP) }
    }

    @Test fun repeatedLinksKeepShortTapAndNativeLongPressSeparate() {
        warmDeviceActivity()
        val opened = AtomicInteger()
        val source = "An ordinary leading paragraph with enough text to expose offset mistakes.\n\n" +
            "Alpha [Guide](https://example.com) Charlie.\n\nAlpha [Guide](https://example.com) Charlie."
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                WorkbenchMiuixTheme(false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                            AiMarkdown(source, onLink = { opened.incrementAndGet() })
                        }
                    }
                }
            } }
            await("Repeated linked paragraphs") { bodyContains("Guide") }
            Thread.sleep(200)
            pressWord("Guide", occurrence = 1)
            await("Short tap still invokes the native link listener") { opened.get() == 1 }
            longPress("Guide", occurrence = 1)
            await("Second identical paragraph has a native selection toolbar") { nativeCopy(false) }
            assertEquals("Long pressing must not open the link", 1, opened.get())
            capture("repeated-link-second-selected")
            assertTrue("Native Copy remains available", nativeCopy(true))
            await("Copied only the linked word from the correct repeated paragraph") { clipboard() == "Guide" }
            pressWord("Guide", occurrence = 1)
            await("Link tapping is restored after Copy") { opened.get() == 2 }
        }
    }

    @Test fun linksAtParagraphListAndTableStartsCopyWithoutExtraCharacters() {
        warmDeviceActivity()
        val source = mutableStateOf("")
        val opened = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                WorkbenchMiuixTheme(false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                            AiMarkdown(source.value, onLink = { opened.incrementAndGet() })
                        }
                    }
                }
            } }
            val cases = listOf(
                "Earlier paragraph.\n\n[Guide](https://example.com)" to "Guide",
                "Earlier paragraph.\n\n[中](https://example.com)" to "中",
                "Earlier paragraph.\n\n- [Guide](https://example.com)" to "Guide",
                "| Header |\n| --- |\n| [中](https://example.com) |" to "中",
            )
            cases.forEachIndexed { index, (markdown, word) ->
                instrumentation.runOnMainSync { source.value = markdown }
                await("Rendered boundary fixture") { bodyContains(word) }
                Thread.sleep(180)
                longPress(word)
                await("Native toolbar for a link at the start of a text leaf") { nativeCopy(false) }
                capture("boundary-$index-selected")
                assertTrue(nativeCopy(true))
                await("Copy has no prefix newline or other characters") { clipboard() == word }
            }
            assertEquals("No long press opens a link", 0, opened.get())
        }
    }

    @Test fun linksRemainAccessibleByKeyboardAndAccessibilityAction() {
        warmDeviceActivity()
        val opened = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                WorkbenchMiuixTheme(false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                            AiMarkdown("Alpha [Guide](https://example.com) Charlie.", onLink = { opened.incrementAndGet() })
                        }
                    }
                }
            } }
            await("Link has a dedicated accessibility action") { nodes { all -> all.any { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Guide") == true &&
                    node.config.contains(SemanticsActions.OnClick)
            } } }
            assertTrue(nodes { all ->
                all.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Guide") == true }
                    .config[SemanticsActions.OnClick].action?.invoke() == true
            })
            assertEquals(1, opened.get())
            // Tab must reach the link without a touch-consuming clickable overlay.
            fun linkFocused() = nodes { all -> all.any { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Guide") == true &&
                    node.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            repeat(4) {
                if (!linkFocused()) {
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
                    Thread.sleep(150)
                }
            }
            assertTrue("Tab can reach a link past the selectable body", linkFocused())
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            await("Enter opens the focused link") { opened.get() == 2 }
        }
    }

    private fun nativeCopy(click: Boolean): Boolean {
        val copyLabel = context.getString(android.R.string.copy)
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.text?.toString() == copyLabel || node.contentDescription?.toString() == copyLabel ||
                node.viewIdResourceName == "android:id/copy") {
                if (!click) return true
                var current: AccessibilityNodeInfo? = node
                while (current != null && !current.isClickable) current = current.parent
                if (current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { if (visit(it)) return true } }
            return false
        }
        val automation = instrumentation.uiAutomation
        return automation.windows.any { it.root?.let(::visit) == true } ||
            automation.rootInActiveWindow?.let(::visit) == true
    }

    private fun clipboard(): String {
        val answer = AtomicReference("")
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            answer.set(manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
        }
        return answer.get()
    }

    private fun capture(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val target = File(context.cacheDir, "ui-review/markdown-selection/$name.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun nativeLongPressCopiesOnlyTheChosenWordInPlainCodeAndLinkedText() {
        warmDeviceActivity()
        val source = mutableStateOf("Alpha Bravo Charlie.")
        val dark = mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                WorkbenchMiuixTheme(dark.value) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                            AiMarkdown(source.value, projectId = "fixture", onOpenProjectPath = {}, onLink = {})
                        }
                    }
                }
            } }
            val cases = listOf("Alpha Bravo Charlie." to "Bravo",
                "Alpha `Bravo` Charlie." to "Bravo",
                "Alpha [Guide](https://example.com) Charlie." to "Guide")
            for (night in listOf(false, true)) for ((index, sample) in cases.withIndex()) {
                instrumentation.runOnMainSync { source.value = sample.first; dark.value = night }
                await("Rendered selectable word") { bodyContains(sample.second) }
                Thread.sleep(180)
                longPress(sample.second)
                await("Native Copy toolbar after genuine long press") { nativeCopy(false) }
                capture("${if (night) "night" else "day"}-$index-selected")
                assertTrue("Click native toolbar Copy", nativeCopy(true))
                await("Only the selected fragment reaches the clipboard") { clipboard() == sample.second }
            }
        }
    }

    @Test fun nativeSelectionSurvivesGrowingReplyAndCompletionThenRevealsLatestAfterCopy() {
        warmDeviceActivity()
        val source = mutableStateOf("Alpha Bravo Charlie.")
        val live = mutableStateOf(true)
        val reading = AtomicInteger()
        val updating = AtomicBoolean(true)
        val stream = Thread {
            var count = 0
            while (updating.get()) {
                Thread.sleep(70)
                if (updating.get()) instrumentation.runOnMainSync { source.value += " tail${++count}" }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                WorkbenchMiuixTheme(false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)
                            .verticalScroll(rememberScrollState())) {
                            CompositionLocalProvider(LocalTextArrival provides live.value) {
                                AiMarkdown(source.value, streaming = live.value,
                                    onReadingInteraction = { reading.incrementAndGet() }, onLink = {})
                            }
                        }
                    }
                }
            } }
            await("Initial body") { bodyContains("Bravo") }
            Thread.sleep(300)
            stream.start()
            try {
                longPress("Bravo")
                await("Native toolbar remains available while reply grows") { nativeCopy(false) && reading.get() > 0 }
                Thread.sleep(400)
                assertTrue("Incoming packets cannot dismiss the toolbar", nativeCopy(false))
                updating.set(false); stream.join(3000)
                instrumentation.runOnMainSync { source.value += " FINAL_RESULT"; live.value = false }
                Thread.sleep(250)
                assertFalse("Keep the selected snapshot stable through completion", bodyContains("FINAL_RESULT"))
                assertTrue("Explicit recovery is available", bodyContains(context.getString(R.string.ai_latest)))
                capture("stream-completed-selection-retained")
                assertTrue("Copy selected fragment through the native toolbar", nativeCopy(true))
                await("Fragment copied") { clipboard() == "Bravo" }
                await("Leaving native selection immediately reveals the received result") { bodyContains("FINAL_RESULT") }
            } finally { updating.set(false); stream.join(3000) }
        }
    }
}
