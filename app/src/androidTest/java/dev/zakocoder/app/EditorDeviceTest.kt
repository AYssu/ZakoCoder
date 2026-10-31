package dev.zakocoder.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real application navigation and native IME. Owns only its created UUID project. */
class EditorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val workbench get() = (instrumentation.targetContext.applicationContext as ZakoApplication).workbench
    @Before fun windows() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    @Test(timeout = 90000) fun visibleImeAndStoppedActivityRetainUnsavedBuffer(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "editor-lifecycle-${java.util.UUID.randomUUID()}"
                instrumentation.runOnMainSync { workbench.create(name) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val project = requireNotNull(workbench.state.value.project); owned = project.id
                val source = withContext(Dispatchers.IO) {
                    File(workbench.projects.directory(project.id), "main.py").apply { writeText("print('saved source')\n") }
                }
                click("浏览文件"); click("main.py")
                await { editor(scenario) != null && !workbench.editor.state.value.loading }
                val view = requireNotNull(editor(scenario))
                await("editor attached and focused application window before keyboard touch") {
                    var ready = false
                    instrumentation.runOnMainSync { ready = ViewCompat.isAttachedToWindow(view) && view.hasWindowFocus() && view.width > 0 && view.height > 0 }
                    ready
                }
                tapEditorWithRealInput(view)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    view.requestFocus()
                    view.showSoftInput()
                    view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                await("real software keyboard becomes visible") { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                screenshot("editor-ime-visible")
                val visible = android.graphics.Rect()
                val frame = android.graphics.Rect()
                instrumentation.runOnMainSync {
                    assertTrue("Editor remains visible with the real IME", view.getGlobalVisibleRect(visible))
                    view.getWindowVisibleDisplayFrame(frame)
                }
                assertTrue("Editor has usable space above the keyboard", visible.height() > 0 && visible.top < frame.bottom)
                input(scenario, "# unsaved across stop/resume\n")
                val draft = workbench.editor.state.value.document!!.content.toString()
                instrumentation.runOnMainSync { view.hideSoftInput() }
                await("software keyboard hides before Activity stop") { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue(workbench.editor.state.value.dirty)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("unsaved editor buffer restored after Activity resumes") { editor(scenario)?.text?.toString() == draft }
                assertTrue(workbench.editor.state.value.dirty)
                assertEquals("print('saved source')\n", withContext(Dispatchers.IO) { source.readText() })
                screenshot("editor-ime-lifecycle")
            } finally {
                withContext(NonCancellable) {
                    await { !workbench.editor.state.value.loading && !workbench.editor.state.value.saving }
                    instrumentation.runOnMainSync { workbench.editor.close() }
                    owned?.let { id ->
                        instrumentation.runOnMainSync { workbench.removeProject(id) }
                        await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } }
                    }
                    previous?.let { id -> instrumentation.runOnMainSync { workbench.select(id) }; await { !workbench.state.value.busy } }
                }
            }
        }
    }
    @Test(timeout = 150000) fun editSaveSearchRecreateDiscardAndConflict(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            assertNull(workbench.editor.state.value.projectId)
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "editor-check-${System.currentTimeMillis()}"
                instrumentation.runOnMainSync { workbench.create(name) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val project = workbench.state.value.project!!; owned = project.id
                val original = "def greet():\r\n    print('你好')\r\n\r\ngreet()\r\n"
                val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                val source = withContext(Dispatchers.IO) {
                    File(workbench.projects.directory(project.id), "main.py").also { it.writeBytes(bom + original.toByteArray()); it.setExecutable(true) }
                }
                click("浏览文件"); click("main.py")
                await { editor(scenario) != null && workbench.editor.state.value.document != null }
                assertEquals(original, workbench.editor.state.value.document!!.content.toString())
                assertFalse(workbench.editor.state.value.dirty)
                await { colored(scenario, 0) }
                input(scenario, "# 中文输入\r\n")
                await { workbench.editor.state.value.dirty }
                assertTrue(workbench.editor.state.value.document!!.content.toString().contains("# 中文输入"))
                click("撤销")
                await { !workbench.editor.state.value.dirty }
                click("重做")
                await { workbench.editor.state.value.dirty }
                click("保存")
                await { !workbench.editor.state.value.saving && !workbench.editor.state.value.dirty }
                withContext(Dispatchers.IO) {
                    val bytes = source.readBytes()
                    assertArrayEquals(bom, bytes.take(3).toByteArray())
                    assertTrue(bytes.toString(Charsets.UTF_8).contains(original))
                    assertTrue(bytes.toString(Charsets.UTF_8).contains("# 中文输入"))
                    assertTrue(source.canExecute())
                }
                click("查找与替换")
                setField(0, "你好")
                await { editor(scenario)?.searcher?.takeIf { it.hasQuery() }?.matchedPositionCount == 1 }
                click("下一个匹配")
                setField(1, "世界")
                click("替换")
                await { workbench.editor.state.value.document!!.content.toString().contains("世界") }
                setField(0, "greet")
                await { editor(scenario)?.searcher?.takeIf { it.hasQuery() }?.matchedPositionCount == 2 }
                setField(1, "welcome")
                click("全部")
                await { !workbench.editor.state.value.document!!.content.toString().contains("greet") }
                assertTrue(workbench.editor.state.value.document!!.content.toString().contains("welcome()"))
                click("撤销")
                await { workbench.editor.state.value.document!!.content.toString().contains("greet()") }
                screenshot("editor-search")
                click("关闭查找")
                val buffer = workbench.editor.state.value.document!!.content
                scenario.recreate()
                await { editor(scenario)?.text === buffer }
                assertTrue(workbench.editor.state.value.dirty)
                assertTrue(editor(scenario)!!.canUndo())
                click("撤销")
                await { !workbench.editor.state.value.document!!.content.toString().contains("世界") }
                click("重做")
                await { workbench.editor.state.value.document!!.content.toString().contains("世界") }
                input(scenario, "# activity draft\r\n")
                val beforeRecreate = workbench.editor.state.value.document!!.content.toString()
                scenario.recreate()
                await { editor(scenario)?.text?.toString() == beforeRecreate }
                click("撤销")
                await { !workbench.editor.state.value.document!!.content.toString().contains("activity draft") }
                click("返回文件列表")
                click("继续编辑")
                assertNotNull(editor(scenario))
                click("返回文件列表")
                click("保存并离开")
                await { workbench.editor.state.value.projectId == null }
                assertTrue(withContext(Dispatchers.IO) { source.readText().contains("世界") })
                click("main.py")
                await { editor(scenario) != null }
                input(scenario, "# 本地草稿\r\n")
                click("返回文件列表")
                screenshot("editor-unsaved")
                click("放弃修改")
                await { workbench.editor.state.value.projectId == null }
                assertFalse(withContext(Dispatchers.IO) { source.readText().contains("本地草稿") })
                click("main.py")
                await { editor(scenario) != null }
                input(scenario, "# 保留缓冲区\r\n")
                val unsaved = workbench.editor.state.value.document!!.content.toString()
                withContext(Dispatchers.IO) { source.writeText("# external edit\nprint('磁盘新内容')\n") }
                click("保存")
                await { !workbench.editor.state.value.saving && workbench.editor.state.value.conflict }
                assertEquals(unsaved, workbench.editor.state.value.document!!.content.toString())
                assertEquals("# external edit\nprint('磁盘新内容')\n", withContext(Dispatchers.IO) { source.readText() })
                screenshot("editor-conflict")
                click("重新加载文件"); click("取消")
                assertEquals(unsaved, workbench.editor.state.value.document!!.content.toString())
                click("重新加载文件"); click("重新加载")
                await { !workbench.editor.state.value.loading && !workbench.editor.state.value.dirty && !workbench.editor.state.value.conflict }
                assertEquals("# external edit\nprint('磁盘新内容')\n", workbench.editor.state.value.document!!.content.toString())
                editor(scenario)?.let { view -> instrumentation.runOnMainSync { view.hideSoftInput() } }
                screenshot("editor-code")
                click("返回文件列表"); click("返回项目库")
            } finally {
                withContext(NonCancellable) {
                    await { !workbench.editor.state.value.loading && !workbench.editor.state.value.saving }
                    instrumentation.runOnMainSync { workbench.editor.close() }
                    owned?.let { id ->
                        instrumentation.runOnMainSync { workbench.removeProject(id) }
                        await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } }
                    }
                    previous?.let { id -> instrumentation.runOnMainSync { workbench.select(id) }; await { !workbench.state.value.busy } }
                }
            }
        }
    }
    @Test(timeout = 60000) fun invalidAndLargeFilesNeverBecomeEditable(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "editor-boundaries-${System.currentTimeMillis()}"
                instrumentation.runOnMainSync { workbench.create(name) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val project = workbench.state.value.project!!; owned = project.id
                val root = withContext(Dispatchers.IO) { workbench.projects.directory(project.id).also {
                    File(it, "binary.bin").writeBytes(byteArrayOf(0, 1, 2))
                    File(it, "invalid.txt").writeBytes(byteArrayOf(0xFF.toByte()))
                    File(it, "large.txt").writeText("a\n".repeat(600000))
                    File(it, "empty.txt").writeText("")
                } }
                click("浏览文件")
                for (nameFile in listOf("binary.bin", "invalid.txt", "large.txt")) {
                    val before = withContext(Dispatchers.IO) { File(root, nameFile).readBytes() }
                    click(nameFile)
                    await { !workbench.editor.state.value.loading && workbench.editor.state.value.error.isNotEmpty() }
                    assertNull(editor(scenario)); assertNull(workbench.editor.state.value.document)
                    click("返回文件列表")
                    assertArrayEquals(before, withContext(Dispatchers.IO) { File(root, nameFile).readBytes() })
                }
                click("empty.txt"); await { editor(scenario) != null }
                input(scenario, "空文件也能编辑")
                click("保存"); await { !workbench.editor.state.value.saving && !workbench.editor.state.value.dirty }
                assertEquals("空文件也能编辑", withContext(Dispatchers.IO) { File(root, "empty.txt").readText() })
                click("返回文件列表"); click("返回项目库")
            } finally {
                withContext(NonCancellable) {
                    await { !workbench.editor.state.value.loading && !workbench.editor.state.value.saving }
                    instrumentation.runOnMainSync { workbench.editor.close() }
                    owned?.let { id -> instrumentation.runOnMainSync { workbench.removeProject(id) }; await { !workbench.state.value.busy } }
                    previous?.let { id -> instrumentation.runOnMainSync { workbench.select(id) }; await { !workbench.state.value.busy } }
                }
            }
        }
    }
    @Test(timeout = 150000) fun lexicalColorsAndLargeEditableText(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "editor-languages-${System.currentTimeMillis()}"
                instrumentation.runOnMainSync { workbench.create(name) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val project = workbench.state.value.project!!; owned = project.id
                val examples = linkedMapOf(
                    "code.kt" to "fun main() { println(42) }\n",
                    "code.java" to "public class Code { int value = 42; }\n",
                    "code.js" to "const value = 42; // note\n",
                    "code.ts" to "const value: number = 42;\n",
                    "code.py" to "def main(): # note\n    return 42\n",
                    "code.sh" to "export VALUE=42 # note\n",
                    "code.json" to "{\"enabled\": true, \"count\": 42}\n",
                    "code.cpp" to "int main() { return 42; }\n",
                )
                val root = withContext(Dispatchers.IO) { workbench.projects.directory(project.id).also { root ->
                    examples.forEach { (file, text) -> File(root, file).writeText(text) }
                    File(root, "large-valid.txt").writeText(("alpha".repeat(10) + "\n").repeat(18000))
                } }
                click("浏览文件")
                for ((file, text) in examples) {
                    click(file); await("native editor opened for $file") { editor(scenario) != null }
                    await("lexical spans ready for $file") { colored(scenario, 0) }
                    assertEquals(text, editor(scenario)!!.text.toString())
                    click("返回文件列表")
                }
                scrollFileIntoView("large-valid.txt")
                click("large-valid.txt"); await("large valid text editor opened") { editor(scenario) != null }
                assertEquals(18001, editor(scenario)!!.lineCount)
                input(scenario, "尾行可编辑")
                click("保存"); await { !workbench.editor.state.value.saving && !workbench.editor.state.value.dirty }
                assertTrue(withContext(Dispatchers.IO) { File(root, "large-valid.txt").readText().endsWith("尾行可编辑") })
                click("返回文件列表"); click("返回项目库")
            } finally {
                withContext(NonCancellable) {
                    await { !workbench.editor.state.value.loading && !workbench.editor.state.value.saving }
                    instrumentation.runOnMainSync { workbench.editor.close() }
                    owned?.let { id -> instrumentation.runOnMainSync { workbench.removeProject(id) }; await { !workbench.state.value.busy } }
                    previous?.let { id -> instrumentation.runOnMainSync { workbench.select(id) }; await { !workbench.state.value.busy } }
                }
            }
        }
    }
    private fun colored(scenario: ActivityScenario<MainActivity>, line: Int): Boolean {
        val view = editor(scenario) ?: return false
        var colored = false
        instrumentation.runOnMainSync {
            val spans = view.styles?.spans?.read()?.getSpansOnLine(line)
            colored = spans != null && spans.map { it.style }.distinct().size > 1
        }
        return colored
    }
    private fun editor(scenario: ActivityScenario<MainActivity>): CodeEditor? {
        var found: CodeEditor? = null
        fun walk(v: View) { if (v is CodeEditor) found = v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        scenario.onActivity { walk(it.window.decorView) }; return found
    }
    private suspend fun tapEditorWithRealInput(view: CodeEditor) {
        val attempts = mutableListOf<String>()
        repeat(3) { attempt ->
            val bounds = android.graphics.Rect()
            var ready = false
            await("focused editor before real touch attempt ${attempt + 1}") {
                instrumentation.runOnMainSync {
                    ready = ViewCompat.isAttachedToWindow(view) && view.hasWindowFocus() &&
                        view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0
                }
                ready
            }
            val x = bounds.left + bounds.width() / 3f
            val y = bounds.top + minOf(bounds.height() / 4f, 80 * view.resources.displayMetrics.density)
            val downTime = android.os.SystemClock.uptimeMillis()
            fun inject(action: Int): Boolean {
                val time = android.os.SystemClock.uptimeMillis()
                val event = android.view.MotionEvent.obtain(downTime, time, action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                return try { instrumentation.uiAutomation.injectInputEvent(event, true) }
                finally { event.recycle() }
            }
            val down = inject(android.view.MotionEvent.ACTION_DOWN)
            delay(60)
            val up = inject(android.view.MotionEvent.ACTION_UP)
            val detail = "attempt=${attempt + 1} down=$down up=$up bounds=$bounds x=$x y=$y downTime=$downTime attached=${ViewCompat.isAttachedToWindow(view)} windowFocus=${view.hasWindowFocus()} editorFocus=${view.hasFocus()}"
            attempts += detail
            android.util.Log.i("CompatibilityEditor", detail)
            if (down && up) return
            inject(android.view.MotionEvent.ACTION_CANCEL)
            delay(150)
        }
        recordFailure("Inject a complete real editor touch", attempts.joinToString("; "))
        throw AssertionError("No complete real editor touch injected: ${attempts.joinToString("; ")}")
    }
    private suspend fun input(scenario: ActivityScenario<MainActivity>, value: String) {
        val view = editor(scenario) ?: error("Missing CodeEditor")
        await("attached, focused and editable native input connection") {
            var ready = false
            instrumentation.runOnMainSync {
                if (ViewCompat.isAttachedToWindow(view) && view.hasWindowFocus() &&
                    !workbench.editor.state.value.loading) {
                    view.requestFocus()
                    ready = view.hasFocus() && view.isEditable && view.onCheckIsTextEditor()
                }
            }
            ready
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            view.setSelection(view.lineCount - 1, view.text.getColumnCount(view.lineCount - 1))
            val connection = requireNotNull(view.onCreateInputConnection(EditorInfo())) { "Focused editor returned no input connection" }
            // A fresh connection is used exactly once: retries could insert the same text twice.
            assertTrue("Fresh native input connection commits text: attached=${ViewCompat.isAttachedToWindow(view)} windowFocus=${view.hasWindowFocus()} focus=${view.hasFocus()} editable=${view.isEditable}",
                connection.commitText(value, 1))
        }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }
            .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }.mapNotNull(::walk).firstOrNull()
    }
    private suspend fun setField(index: Int, value: String) {
        val targets = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isEditable && n.isVisibleToUser && n.contentDescription?.toString() != "代码编辑区") targets += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        await {
            targets.clear()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
            targets.size > index
        }
        assertTrue(targets[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        delay(250)
    }
    private suspend fun click(label: String) {
        await("visible navigation control: $label") { find(label) != null }
        var node = find(label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); delay(250)
    }
    private suspend fun scrollFileIntoView(fileName: String) {
        repeat(8) {
            if (find(fileName)?.isVisibleToUser == true) return
            var scrollable: AccessibilityNodeInfo? = null
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser && node.isScrollable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) scrollable = node
                repeat(node.childCount) { node.getChild(it)?.let(::visit) }
            }
            instrumentation.uiAutomation.windows.mapNotNull { it.root }
                .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }.forEach(::visit)
            val target = requireNotNull(scrollable) { "No project file list can scroll to $fileName" }
            assertTrue("Scroll project file list toward $fileName", target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            delay(250)
        }
        await("file visible after scrolling its project list: $fileName") { find(fileName)?.isVisibleToUser == true }
    }
    private suspend fun await(label: String = "editor state", condition: suspend () -> Boolean) {
        val location = Throwable().stackTrace.firstOrNull {
            it.className.startsWith(EditorDeviceTest::class.java.name) && !it.methodName.startsWith("await")
        }
        val ready = withTimeoutOrNull(20000) { while (!condition()) delay(100); true }
        if (ready != true) {
            val failure = AssertionError("Editor stage timed out: $label at $location")
            try { recordFailure(label, location.toString()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (diagnostic: Exception) { failure.addSuppressed(diagnostic) }
            throw failure
        }
    }
    private suspend fun recordFailure(label: String, location: String) {
        val state = workbench.editor.state.value
        val lines = mutableListOf("stage=$label at $location",
            "selectedProject=${workbench.state.value.selectedId} appLoading=${workbench.state.value.loading} appBusy=${workbench.state.value.busy}",
            "editorProject=${state.projectId} path=${state.path} loading=${state.loading} saving=${state.saving} dirty=${state.dirty} conflict=${state.conflict} error=${state.error} tabs=${state.tabs.size} documentChars=${state.document?.content?.length}")
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= 200) return
            if (node.isVisibleToUser) {
                val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                lines += "${" ".repeat(depth.coerceAtMost(12))}package=${node.packageName} id=${node.viewIdResourceName} text=${node.text?.take(300)} desc=${node.contentDescription} clickable=${node.isClickable} bounds=$rect"
            }
            repeat(node.childCount) { node.getChild(it)?.let { child -> walk(child, depth + 1) } }
        }
        instrumentation.uiAutomation.windows.forEach { it.root?.let { root -> walk(root, 0) } }
        val image = instrumentation.uiAutomation.takeScreenshot()
        try { withContext(Dispatchers.IO) {
            val directory = File(instrumentation.targetContext.cacheDir,
                "compatibility/editor/api-${android.os.Build.VERSION.SDK_INT}/${java.util.UUID.randomUUID()}").apply { mkdirs() }
            File(directory, "failure.txt").writeText(lines.joinToString("\n"))
            image?.let { bitmap -> File(directory, "failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            println("EDITOR_DIAGNOSTICS ${directory.absolutePath}")
        } } finally { image?.recycle() }
        lines.forEach { println("EDITOR_DIAGNOSTIC $it") }
    }
    private suspend fun screenshot(name: String) {
        delay(300)
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "system"
            val directory = File(instrumentation.targetContext.cacheDir, "ui-review/$theme").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
    }
}
