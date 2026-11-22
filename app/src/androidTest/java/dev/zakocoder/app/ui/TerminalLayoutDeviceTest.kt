package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import dev.zakocoder.app.*
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.ai.AiReasoningEffort
import dev.zakocoder.app.ai.AiThinking
import dev.zakocoder.app.ai.AiCatalog
import dev.zakocoder.app.ai.AiModelsState
import dev.zakocoder.app.ai.AiProfile
import dev.zakocoder.app.ai.AiParameters
import dev.zakocoder.app.terminal.TerminalManager
import dev.zakocoder.app.runtime.RuntimeCommand
import dev.zakocoder.app.settings.OnboardingProgress
import dev.zakocoder.app.settings.OnboardingRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real, owned Android PTYs exercise layout/input/navigation without installing Ubuntu or using credentials. */
class TerminalLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private suspend fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { label }; delay(40) }
    }
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            all += node
            if (!node.config.isClearingSemantics) node.children.forEach(::collect)
        }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all)
    }
    private fun sliderScreenBounds(): androidx.compose.ui.geometry.Rect = main {
        var result: androidx.compose.ui.geometry.Rect? = null
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) {
                val screen = IntArray(2); val window = IntArray(2)
                view.getLocationOnScreen(screen); view.getLocationInWindow(window)
                fun find(node: SemanticsNode) {
                    if (node.config.getOrNull(SemanticsProperties.TestTag) == "ai-reasoning-slider") {
                        result = node.boundsInWindow.translate(androidx.compose.ui.geometry.Offset(
                            (screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()))
                    }
                    node.children.forEach(::find)
                }
                find(view.semanticsOwner.unmergedRootSemanticsNode)
            }
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        checkNotNull(result)
    }
    private fun modelPopupWindowBounds(): android.graphics.Rect? = main {
        var result: android.graphics.Rect? = null
        fun containsPopup(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.TestTag) == "ai-model-popover" || node.children.any(::containsPopup)
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest && containsPopup(view.semanticsOwner.unmergedRootSemanticsNode)) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                result = android.graphics.Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        result
    }
    // Accessibility hides a closing dialog before its Android window is removed.
    // Track that window itself so semantic test actions cannot click through its exit.
    private fun modalWindow(resource: Int): View = main {
        fun contains(node: SemanticsNode): Boolean = matches(node, resource) || node.children.any(::contains)
        fun contains(view: View): Boolean {
            if (!view.isShown) return false
            if (view is ViewRootForTest && contains(view.semanticsOwner.unmergedRootSemanticsNode)) return true
            return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
        }
        checkNotNull(WindowInspector.getGlobalWindowViews().firstOrNull { contains(it) })
    }
    private fun matches(node: SemanticsNode, resource: Int): Boolean {
        val bounds = node.boundsInWindow
        if (bounds.width <= 0 || bounds.height <= 0 || bounds.center.x <= 0 || bounds.center.x >= base.resources.displayMetrics.widthPixels) return false
        return node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == base.getString(resource) } == true ||
            base.getString(resource) in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private suspend fun click(resource: Int) {
        await("action visible: ${base.getString(resource)}") { nodes { it.any { node -> matches(node, resource) } } }
        nodes { all ->
            var node: SemanticsNode? = all.last { matches(it, resource) }
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private suspend fun clickModel(value: String) {
        fun matched(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        await("model row visible") { nodes { it.any(::matched) } }
        nodes { all ->
            var node: SemanticsNode? = all.last(::matched)
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun terminal(scenario: ActivityScenario<MainActivity>): TerminalView? {
        var result: TerminalView? = null
        fun visit(view: View) {
            if (view is TerminalView) result = view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        scenario.onActivity { visit(it.window.decorView) }
        return result
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(400)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(base.cacheDir, "ui-review/terminal-layout/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test(timeout = 60_000) fun modelListBelongsToActiveProfileAndScrollsWithoutSearch(): Unit = runBlocking {
        val profiles = listOf(
            AiProfile("deepseek", "fixture DeepSeek", "http://127.0.0.1", "deepseek-23", false,
                AiParameters(), modelChoices = (0..24).map { "deepseek-%02d".format(it) } + "shared-model"),
            AiProfile("gpt", "fixture GPT", "http://127.0.0.1", "gpt-00", false,
                AiParameters(), modelChoices = (0..24).map { "gpt-%02d".format(it) } + "shared-model"))
        var state by mutableStateOf(AiModelsState(AiCatalog("deepseek", profiles), loading = false))
        val chosen = AtomicReference<Pair<String, String>>()
        fun visible(prefix: String) = nodes { all -> all.any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
        } }
        suspend fun scrollTo(index: Int) {
            val action = nodes { all -> all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-model-saved-list" }
                .config[SemanticsActions.ScrollToIndex].action!! }
            withContext(Dispatchers.Main) { assertTrue(action(index)) }
        }
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                main { state = state.copy(catalog = state.catalog.copy(activeId = "deepseek")) }
                scenario.onActivity { activity -> activity.setContent {
                    WorkbenchMiuixTheme(dark) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
                                Surface(Modifier.width(240.dp).heightIn(max = 360.dp)) {
                                    AiModelPickerContent(state, false, onSelect = { id, model -> chosen.set(id to model) },
                                        onReasoning = { _, _, _ -> }, onRetry = {}, onManage = {})
                                }
                            }
                        }
                    }
                } }
                await("opens at selected model in active provider") { visible("deepseek-23") && !visible("gpt-") }
                assertFalse("long lists must not introduce a search editor", nodes { all -> all.any { it.config.contains(SemanticsActions.SetText) } })
                capture("model-list-deepseek-${if (dark) "night" else "day"}")
                scrollTo(24)
                await("late model reachable") { visible("deepseek-24") }
                clickModel("deepseek-24")
                assertEquals("deepseek" to "deepseek-24", chosen.get())
                main { state = state.copy(catalog = state.catalog.copy(activeId = "gpt")) }
                await("provider switch resets scroll and removes old choices") { visible("gpt-00") && !visible("deepseek-") }
                capture("model-list-gpt-${if (dark) "night" else "day"}")
                scrollTo(25)
                await("shared name visible in active provider") { visible("shared-model") }
                clickModel("shared-model")
                assertEquals("gpt" to "shared-model", chosen.get())
                main { state = state.copy(catalog = state.catalog.copy(activeId = "deepseek")) }
                await("previous provider retains its saved models") { visible("deepseek-23") && !visible("gpt-") }
                assertEquals(profiles, state.catalog.profiles)
            }
        }
    }

    @Test(timeout = 75_000) fun terminalInputRetainsFocusAndArrowHoldsRepeatUntilReleased(): Unit = runBlocking {
        val manager = TerminalManager(base) {}
        withContext(Dispatchers.IO) { manager.prepareTypeface() }
        val command = RuntimeCommand("/system/bin/sh", listOf("-i"), mapOf("PATH" to "/system/bin", "TERM" to "xterm-256color", "PS1" to "$ "))
        val first = main { manager.create("owned-input", "input fixture", command) }
        val second = main { manager.create("owned-input", "input fixture", command) }
        var current by mutableStateOf(first)
        val counts = mutableMapOf<Int, Int>()
        var native: TerminalView? = null
        warmDeviceActivity()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    WorkbenchMiuixTheme(false) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = androidx.compose.ui.graphics.Color.Black) {
                            Column {
                                AndroidView(factory = { manager.view(it, current, 9).also { view -> native = view } },
                                    update = { manager.bind(it, current) }, onRelease = manager::detach,
                                    modifier = Modifier.fillMaxWidth().weight(1f))
                                TerminalControls(false, false,
                                    onKey = { code, ctrl -> counts[code] = (counts[code] ?: 0) + 1; manager.key(checkNotNull(native), code, ctrl) },
                                    onControl = {}, onAlt = {}, onKeyboard = { manager.toggleKeyboard(checkNotNull(native)) },
                                    onSwitch = { current = if (current.id == first.id) second else first },
                                    menu = false, onMenu = {}, canCreate = false, onPreview = {}, onCreate = {}, onClose = {})
                            }
                        }
                    }
                } }
                await("native view focused") { main { native?.hasFocus() == true } }
                val view = checkNotNull(native)
                main { manager.hideKeyboard(view) }
                assertTrue("hiding keyboard must preserve terminal input focus", main { view.hasFocus() })
                // A helper key can also recover focus left by another control.
                main { view.clearFocus(); manager.key(view, KeyEvent.KEYCODE_MOVE_END) }
                assertTrue("helper keys restore native editor focus", main { view.hasFocus() })
                repeat(3) { index ->
                    click(R.string.switch_terminals)
                    await("PTY switches in the same focused view") { main { view.currentSession === current.session && view.hasFocus() && view.hasWindowFocus() } }
                    assertSame(view, native)
                    // Deliver through Activity dispatch, not a direct InputConnection call that bypasses focus.
                    instrumentation.sendStringSync("echo INPUT_AFTER_SWITCH_$index")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                    try {
                        await("physical input reaches selected PTY immediately") {
                            main { current.session.emulator.screen.transcriptText.lineSequence().any { it.trim() == "INPUT_AFTER_SWITCH_$index" } }
                        }
                    } catch (failure: IllegalStateException) {
                        println("OWNED_PTY_INPUT_FAILURE " + main { current.session.emulator.screen.transcriptText })
                        throw failure
                    }
                }
                // Use a real raw PTY reader so repeated arrows can be checked as bytes as well as callbacks.
                main { current.session.write("stty raw -echo; printf 'RAW_READY\\r\\n'; while true; do dd bs=1 count=3 2>/dev/null | od -An -tx1; done\n") }
                await("raw reader ready") { main { current.session.emulator.screen.transcriptText.lineSequence().any { it.trim() == "RAW_READY" } } }
                for ((label, code, hex) in listOf(Triple(R.string.left_key, KeyEvent.KEYCODE_DPAD_LEFT, "1b 5b 44"),
                    Triple(R.string.right_key, KeyEvent.KEYCODE_DPAD_RIGHT, "1b 5b 43"))) {
                    val rect = nodes { all -> all.last { matches(it, label) }.boundsInWindow }
                    val started = SystemClock.uptimeMillis()
                    fun touch(action: Int, x: Float = rect.center.x, y: Float = rect.center.y) {
                        val event = android.view.MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
                        try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                    }
                    val before = main { counts[code] ?: 0 }
                    touch(android.view.MotionEvent.ACTION_DOWN)
                    try {
                        delay(850)
                        capture("terminal-held-${if (code == KeyEvent.KEYCODE_DPAD_LEFT) "left" else "right"}")
                        assertTrue("long hold emits multiple direction keys", main { (counts[code] ?: 0) - before >= 3 })
                    } finally { touch(android.view.MotionEvent.ACTION_UP) }
                    val stopped = main { counts[code] ?: 0 }
                    delay(220)
                    assertEquals("release stops repeat without an extra click", stopped, main { counts[code] ?: 0 })
                    await("repeat bytes reach actual PTY") { main { current.session.emulator.screen.transcriptText.contains(hex) } }
                    touch(android.view.MotionEvent.ACTION_DOWN)
                    touch(android.view.MotionEvent.ACTION_UP)
                    await("short tap delivers exactly once") { main { (counts[code] ?: 0) == stopped + 1 } }
                    touch(android.view.MotionEvent.ACTION_DOWN)
                    touch(android.view.MotionEvent.ACTION_CANCEL)
                    val cancelled = main { counts[code] ?: 0 }
                    delay(600)
                    assertEquals("cancelled touch must not repeat", cancelled, main { counts[code] ?: 0 })
                }
                capture("terminal-feedback-idle")
            }
        } finally { withContext(NonCancellable + Dispatchers.Main) { manager.closeAll() } }
    }

    @Test(timeout = 150_000) fun assistantEntryTwoRowsInputSwitchAndBackPreserveSessions(): Unit = runBlocking {
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(base) }
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(
                "${fixture.token}-$name".also { synchronized(preferences) { preferences += it } }, mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        try {
            warmDeviceActivity()
            withContext(Dispatchers.IO) {
                OnboardingRepository(isolated).save(OnboardingProgress(completed = true))
                isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
            }
            val host = main { Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }.also { owned = it }
            await("workbench initialized") { !host.state.value.loading && !host.ai.models.state.value.loading }
            main { host.create("终端布局验收") }
            await("owned workspace created") { !host.state.value.busy && host.state.value.project != null }
            val project = host.state.value.project!!
            main { host.ai.select(project.id) }
            await("assistant workspace ready") { host.ai.state.value.projectId == project.id && !host.ai.state.value.restoring }
            withContext(Dispatchers.IO) {
                fixture.config.updateModelChoices("legacy", listOf("fixture-code", "gpt-6.1-sol"))
                host.terminal.prepareTypeface()
            }
            main { host.ai.models.retry() }
            await("saved models loaded") { !host.ai.models.state.value.loading && host.ai.models.state.value.catalog.active?.modelChoices?.size == 2 }
            val alternate = main { host.terminal.create(project.id, project.name, RuntimeCommand("/system/bin/sh", listOf("-i"), mapOf("PATH" to "/system/bin", "HOME" to isolated.filesDir.path, "PS1" to "$ "))) }
            val first = main { host.terminal.create(project.id, project.name, RuntimeCommand("/system/bin/sh", listOf("-i"), mapOf("PATH" to "/system/bin", "HOME" to isolated.filesDir.path, "PS1" to "$ "))) }
            main { first.session.write("printf 'TERMINAL_LAYOUT_READY\\n'\n") }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    scenario.onActivity { activity ->
                        val config = Configuration(activity.resources.configuration).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        activity.setContent { CompositionLocalProvider(LocalConfiguration provides config) { ZakoCoderApp(host) } }
                    }
                    await("assistant mounted") { nodes { it.any { node -> matches(node, R.string.ai_more) } } }
                    val conversation = host.ai.state.value.conversationId
                    assertTrue("owned PTY is exposed in workbench state", host.state.value.sessions.any { it.id == first.id })
                    assertEquals(project.id, host.ai.state.value.projectId)
                    delay(800)
                    click(R.string.ai_more)
                    delay(400)
                    capture("assistant-menu-${if (dark) "night" else "day"}")
                    click(R.string.nav_terminal)
                    await("terminal opened directly from assistant") { terminal(scenario)?.currentSession === first.session }
                    val view = checkNotNull(terminal(scenario))
                    assertEquals(android.graphics.Color.BLACK, main { first.session.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] })
                    val firstRow = listOf(R.string.escape_key, R.string.tab_key, R.string.home_key, R.string.up_key, R.string.end_key, R.string.keyboard, R.string.terminal_actions)
                    val secondRow = listOf(R.string.control_key, R.string.alt_key, R.string.left_key, R.string.down_key, R.string.right_key, R.string.terminal_slash_key, R.string.switch_terminals)
                    nodes { all ->
                        val row1 = firstRow.map { label -> all.last { matches(it, label) }.boundsInWindow.center }
                        val row2 = secondRow.map { label -> all.last { matches(it, label) }.boundsInWindow.center }
                        assertTrue(row1.zipWithNext().all { (a, b) -> a.x < b.x && kotlin.math.abs(a.y - b.y) < 2 })
                        assertTrue(row2.zipWithNext().all { (a, b) -> a.x < b.x && kotlin.math.abs(a.y - b.y) < 2 })
                        assertTrue(row2.first().y > row1.first().y)
                    }
                    val viewport = main { val location = IntArray(2); view.getLocationOnScreen(location); location[1] }
                    assertTrue("terminal has safe top breathing room without title", viewport in (8 * base.resources.displayMetrics.density).toInt()..(64 * base.resources.displayMetrics.density).toInt())
                    await("system clock and status indicators stay visible") {
                        main { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                    }
                    assertTrue("navigation remains visible so one Back reaches the page", main {
                        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true
                    })
                    capture("terminal-${if (dark) "night" else "day"}")
                    click(R.string.control_key)
                    assertTrue(host.terminal.modifiers.value.control)
                    main { host.terminal.key(view, KeyEvent.KEYCODE_C) }
                    assertFalse(host.terminal.modifiers.value.control)
                    click(R.string.alt_key); assertTrue(host.terminal.modifiers.value.alt)
                    click(R.string.alt_key); assertFalse(host.terminal.modifiers.value.alt)
                    main { view.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("printf 'KEY_INPUT_OK\\n'", 1); host.terminal.key(view, KeyEvent.KEYCODE_ENTER) }
                    await("native input reaches real PTY") { main { first.session.emulator.screen.transcriptText.lineSequence().any { it.trim() == "KEY_INPUT_OK" } } }
                    click(R.string.keyboard)
                    await("keyboard visible") { main { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true } }
                    await("both rows remain above IME") {
                        val top = main { val decor = view.rootView; decor.height - checkNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.ime()).bottom }
                        nodes { all -> all.last { matches(it, R.string.switch_terminals) }.boundsInWindow.bottom <= top + 1 }
                    }
                    capture("terminal-keyboard-${if (dark) "night" else "day"}")
                    click(R.string.terminal_actions)
                    await("bottom menu available") { nodes { it.any { node -> matches(node, R.string.terminal_preview) } } }
                    val menuWindow = modalWindow(R.string.terminal_preview)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("back fully dismisses menu without leaving terminal") {
                        terminal(scenario) === view &&
                            nodes { it.none { node -> matches(node, R.string.terminal_preview) } } &&
                            main { !menuWindow.isAttachedToWindow }
                    }
                    await("menu cancellation restores terminal focus and the open keyboard") {
                        main { view.hasFocus() && view.hasWindowFocus() &&
                            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    }
                    // Activity dispatch must reach the PTY without touching or refocusing the terminal.
                    val menuInputMarker = "MENU_FOCUS_${if (dark) "NIGHT" else "DAY"}_${SystemClock.uptimeMillis()}"
                    instrumentation.sendStringSync("printf '$menuInputMarker\\n'")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                    await("input after menu cancellation reaches the real PTY") {
                        main { first.session.emulator.screen.transcriptText.lineSequence().any { it.trim() == menuInputMarker } }
                    }
                    capture("terminal-menu-input-${if (dark) "night" else "day"}")
                    click(R.string.keyboard)
                    await("keyboard hidden after menu input verification") {
                        main { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
                    }
                    click(R.string.switch_terminals)
                    await("session sheet visible") { nodes { it.any { node -> matches(node, R.string.terminal_close_hint) } } }
                    suspend fun selectSession(number: Int) {
                        val selectionWindow = modalWindow(R.string.terminal_close_hint)
                        nodes { all ->
                            var node: SemanticsNode? = all.last { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == base.getString(R.string.terminal_number, number) } == true }
                            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
                            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
                        }
                        // Native PTY attachment can precede the sheet window's removal by a frame.
                        await("session selection window fully closes before another action") {
                            main { !selectionWindow.isAttachedToWindow }
                        }
                    }
                    selectSession(alternate.number)
                    await("switch attaches the other PTY") { terminal(scenario)?.currentSession === alternate.session }
                    assertSame("switch retains the native view", view, terminal(scenario))
                    click(R.string.switch_terminals)
                    await("session sheet reopened") { nodes { it.any { node -> matches(node, R.string.terminal_close_hint) } } }
                    selectSession(first.number)
                    await("switch restores original PTY") { terminal(scenario)?.currentSession === first.session }
                    await("terminal window regains focus after session selection") { main { view.hasWindowFocus() } }
                    val imeBeforeBack = main { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) }
                    capture("terminal-before-back-${if (dark) "night" else "day"}")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("one back returns directly to assistant; IME before Back=$imeBeforeBack") { terminal(scenario) == null && nodes { it.any { node -> matches(node, R.string.ai_more) } } }
                    assertEquals(conversation, host.ai.state.value.conversationId)
                    assertTrue(first.session.isRunning)
                    click(R.string.ai_switch_model)
                    await("model popup visible") { nodes { it.any { node -> node.config.getOrNull(SemanticsProperties.TestTag) == "ai-model-popover" } } }
                    capture("model-popover-${if (dark) "night" else "day"}")
                    clickModel("gpt-6.1-sol")
                    await("configured model selected") { !host.ai.models.state.value.saving && host.ai.models.state.value.catalog.active?.model == "gpt-6.1-sol" }
                    await("reasoning slider visible") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-reasoning-slider" } } }
                    nodes { all ->
                        val node = all.last { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-reasoning-slider" }
                        assertTrue(node.config[SemanticsActions.SetProgress].action!!.invoke(0f))
                    }
                    await("first tick saves disabled") { !host.ai.models.state.value.saving && host.ai.models.state.value.catalog.active?.parameters?.thinking == AiThinking.Disabled }
                    nodes { all ->
                        val node = all.last { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-reasoning-slider" }
                        assertTrue(node.config[SemanticsActions.SetProgress].action!!.invoke(1f))
                    }
                    await("second tick saves provider default") { !host.ai.models.state.value.saving && host.ai.models.state.value.catalog.active?.parameters?.thinking == AiThinking.Default }
                    capture("model-default-${if (dark) "night" else "day"}")
                    // Exercise a real touch drag from the first tick to the final tick.
                    val rect = sliderScreenBounds()
                    val downAt = SystemClock.uptimeMillis()
                    fun touch(action: Int, x: Float) {
                        val event = android.view.MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, rect.center.y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                    }
                    touch(android.view.MotionEvent.ACTION_DOWN, rect.left + rect.width / 12)
                    repeat(10) { step ->
                        touch(android.view.MotionEvent.ACTION_MOVE, rect.left + rect.width * (1f / 12 + (step + 1) * 10f / 120))
                        delay(24)
                    }
                    capture("model-reasoning-drag-${if (dark) "night" else "day"}")
                    touch(android.view.MotionEvent.ACTION_UP, rect.right - rect.width / 12)
                    await("drag saves highest level") { !host.ai.models.state.value.saving && host.ai.models.state.value.catalog.active?.parameters?.reasoningEffort == AiReasoningEffort.Max }
                    capture("model-reasoning-${if (dark) "night" else "day"}")
                    nodes { all ->
                        val node = all.last { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-reasoning-slider" }
                        assertTrue(node.config[SemanticsActions.SetProgress].action!!.invoke(if (dark) 3f else 4f))
                    }
                    val expected = if (dark) AiReasoningEffort.Medium else AiReasoningEffort.High
                    await("reasoning saved") { !host.ai.models.state.value.saving && host.ai.models.state.value.catalog.active?.parameters?.reasoningEffort == expected }
                    capture("model-selection-${if (dark) "night" else "day"}")
                    val popupBounds = requireNotNull(modelPopupWindowBounds())
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    // Exit may fade/scale the card, but must not resize/reposition its window or insert task text.
                    var closingSamples = 0
                    repeat(6) {
                        modelPopupWindowBounds()?.let { bounds ->
                            closingSamples++
                            assertEquals("closing keeps popup window anchored", popupBounds, bounds)
                            assertFalse("closing is not an active task", nodes { all -> all.any { matches(it, R.string.ai_models_task_locked) } })
                        }
                        delay(12)
                    }
                    assertTrue("observed popup during exit", closingSamples > 0)
                    await("popup dismissed, original conversation remains") { nodes { it.none { node -> node.config.getOrNull(SemanticsProperties.TestTag) == "ai-model-popover" } } }
                    val originalPolicy = host.ai.state.value.policy
                    click(permissionModeLabel(dev.zakocoder.app.ai.AiPermissionPresets.matching(originalPolicy)))
                    await("permission popover visible") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-popover" } } }
                    nodes { all ->
                        assertTrue(dev.zakocoder.app.ai.AiPermissionPreset.entries.all { preset ->
                            all.any { matches(it, permissionModeLabel(preset)) }
                        })
                        assertFalse(all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-advanced" })
                    }
                    capture("permission-popover-${if (dark) "night" else "day"}")
                    click(R.string.ai_permission_more)
                    await("advanced rules open in popover") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-advanced" } } }
                    capture("permission-advanced-${if (dark) "night" else "day"}")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("back returns to simple permission modes") { nodes { all ->
                        all.none { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-advanced" } &&
                            all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-popover" }
                    } }
                    click(R.string.ai_permission_preset_yolo)
                    await("dangerous permission retains confirmation") { nodes { all -> all.any { matches(it, R.string.ai_permission_yolo_confirm_title) } } }
                    val confirmationWindow = modalWindow(R.string.ai_permission_yolo_confirm_title)
                    click(R.string.cancel)
                    await("cancel finishes the confirmation window exit") {
                        main { !confirmationWindow.isAttachedToWindow }
                    }
                    assertEquals(originalPolicy, host.ai.state.value.policy)
                    click(R.string.ai_permission_preset_elevated)
                    await("auto mode requires confirmation") { nodes { all -> all.any { matches(it, R.string.ai_auto_allow_title) } } }
                    click(R.string.ai_auto_allow_confirm)
                    await("auto saved and popover closes") {
                        !host.ai.state.value.permissionsSaving &&
                            dev.zakocoder.app.ai.AiPermissionPresets.matching(host.ai.state.value.policy) == dev.zakocoder.app.ai.AiPermissionPreset.Elevated &&
                            nodes { all -> all.none { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-popover" } }
                    }
                    click(R.string.ai_permission_preset_elevated)
                    await("permission popover reopens") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-popover" } } }
                    click(R.string.ai_permission_preset_readonly)
                    await("read only saved and popover closes") {
                        !host.ai.state.value.permissionsSaving &&
                            dev.zakocoder.app.ai.AiPermissionPresets.matching(host.ai.state.value.policy) == dev.zakocoder.app.ai.AiPermissionPreset.ReadOnly &&
                            nodes { all -> all.none { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-permission-popover" } }
                    }
                    capture("permission-composer-${if (dark) "night" else "day"}")
                    assertEquals(conversation, host.ai.state.value.conversationId)
                }
            }
            assertEquals(0, fixture.server.requestCount)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.terminal?.closeAll(); owned?.stopAll(); owned?.browser?.close() }
            withContext(NonCancellable + Dispatchers.IO) {
                fixture.close()
                synchronized(preferences) { preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }
}
