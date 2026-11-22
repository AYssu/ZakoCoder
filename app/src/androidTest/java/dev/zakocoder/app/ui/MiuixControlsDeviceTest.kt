package dev.zakocoder.app.ui

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TabRow as MiuixTabRow
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/** Isolated native-control callbacks only: no settings, device grants, commands or network. */
class MiuixControlsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()
    private val fontTrace get() = File(context.cacheDir, "ui-review/miuix-controls/font-touch-diagnostics.txt")
    private fun traceFont(message: String) {
        val line = "${SystemClock.uptimeMillis()} $message"
        synchronized(this) {
            fontTrace.parentFile?.mkdirs()
            fontTrace.appendText("$line\n")
        }
        Log.e("ZakoUiTest", line)
    }

    @Before fun foreground() = warmDeviceActivity()

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }

    private fun fontOption(all: List<SemanticsNode>, size: Int): SemanticsNode? {
        val label = context.getString(R.string.font_size_compact, size)
        return all.asReversed().firstNotNullOfOrNull { node ->
            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) {
                generateSequence(node) { it.parent }.firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                        it.config.contains(SemanticsProperties.Selected)
                }
            } else null
        }
    }
    private fun fontRow(all: List<SemanticsNode>): SemanticsNode? =
        all.asReversed().firstNotNullOfOrNull { node ->
            if (node.config.getOrNull(SemanticsProperties.Text)?.any {
                it.text == context.getString(R.string.terminal_font)
            } == true) {
                generateSequence(node) { it.parent }.firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick) != null ||
                        it.config.contains(SemanticsProperties.Disabled)
                }
            } else null
        }
    /** Input injection uses display coordinates; popup semantics use their own window. */
    private fun screenTouchBounds(node: SemanticsNode): Rect {
        fun contains(root: SemanticsNode): Boolean = root.id == node.id || root.children.any(::contains)
        fun owner(view: View): View? {
            if (!view.isShown) return null
            if (view is ViewRootForTest && contains(view.semanticsOwner.unmergedRootSemanticsNode)) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> owner(view.getChildAt(index))?.let { return it } }
            return null
        }
        val view = requireNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews()
            .firstNotNullOfOrNull(::owner)) { "Compose owner for font control ${node.id}" }
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        val offset = Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        val bounds = node.boundsInWindow.translate(offset)
        traceFont("font-touch id=${node.id} windowBounds=${node.boundsInWindow} " +
            "layoutWindow=${node.layoutInfo.coordinates.localToWindow(Offset.Zero)} layoutSize=${node.size} " +
            "owner=${view.javaClass.simpleName} ownerScreen=${screen.toList()} ownerWindow=${window.toList()} " +
            "screenMinusWindow=$offset injectedBounds=$bounds selected=${node.config.getOrNull(SemanticsProperties.Selected)} " +
            "ancestors=${generateSequence(node) { it.parent }.take(5).joinToString { "${it.id}:${it.boundsInWindow}:${it.config.getOrNull(SemanticsProperties.Role)}" }}")
        return bounds
    }
    private fun fontState(all: List<SemanticsNode>): String = (9..28).mapNotNull { size ->
        fontOption(all, size)?.let { "$size:selected=${it.config.getOrNull(SemanticsProperties.Selected)},bounds=${it.boundsInWindow}" }
    }.joinToString()
    private fun fontScroll(option: SemanticsNode): SemanticsNode =
        requireNotNull(generateSequence(option.parent) { it.parent }.firstOrNull {
            it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }) { "Actual native font menu viewport" }
    private fun settleFontMenu(size: Int) {
        var previous: String? = null
        var stationarySince = SystemClock.uptimeMillis()
        ui.await("Native font menu scroll settles: $size") { nodes { all ->
            val option = fontOption(all, size) ?: return@nodes false
            val scroll = fontScroll(option)
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            val current = "${option.boundsInWindow}:${scroll.boundsInWindow}:${range.value()}"
            if (current != previous) stationarySince = SystemClock.uptimeMillis()
            previous = current
            scroll.boundsInWindow.height > 0 && SystemClock.uptimeMillis() - stationarySince >= 350
        } }
        traceFont("scroll settled size=$size state=$previous")
    }
    private fun chooseFont(size: Int) {
        traceFont("choose start size=$size menu=${nodes(::fontState)}")
        settleFontMenu(size)
        // Use the same physical scroll path as a user. Semantics ScrollBy can leave
        // the native overscroll effect active at a boundary despite static bounds.
        repeat(12) {
            val visible = nodes { all -> fontOption(all, size)?.let {
                it.boundsInWindow.width > 0 && it.boundsInWindow.height >= it.size.height * .95f
            } == true }
            if (visible) {
                val bounds = nodes { all -> screenTouchBounds(requireNotNull(fontOption(all, size))) }
                traceFont("choose ready size=$size screen=$bounds")
                tap(bounds)
                return
            }
            val gesture = nodes { all ->
                val option = requireNotNull(fontOption(all, size))
                val scroll = fontScroll(option)
                val viewport = screenTouchBounds(scroll)
                val visibleSizes = (9..28).filter { candidate -> fontOption(all, candidate)?.boundsInWindow?.height?.let { it > 0 } == true }
                val upwards = size > visibleSizes.average()
                Triple(viewport, upwards, visibleSizes)
            }
            val viewport = gesture.first
            val start = viewport.top + viewport.height * if (gesture.second) .78f else .22f
            val end = viewport.top + viewport.height * if (gesture.second) .22f else .78f
            traceFont("physical scroll target=$size visible=${gesture.third} x=${viewport.center.x} from=$start to=$end")
            val down = SystemClock.uptimeMillis()
            event(down, MotionEvent.ACTION_DOWN, viewport.center.x, start)
            repeat(24) { frame ->
                SystemClock.sleep(15)
                event(down, MotionEvent.ACTION_MOVE, viewport.center.x, start + (end - start) * (frame + 1) / 24f)
            }
            event(down, MotionEvent.ACTION_UP, viewport.center.x, end)
            settleFontMenu(size)
        }
        error("Font option cannot be physically revealed: $size; ${nodes(::fontState)}")
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            traceFont("font-input action=$action source=${event.source} x=$x y=$y " +
                "downTime=$down eventTime=${event.eventTime}")
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        }
        finally { event.recycle() }
    }
    private fun tap(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y)
        SystemClock.sleep(80)
        event(down, MotionEvent.ACTION_UP, bounds.center.x, bounds.center.y)
    }

    @Test(timeout = 120_000) fun terminalFontMenuSelectionCancelAndDisabledInBothThemes() {
        fontTrace.delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                traceFont("theme dark=$dark")
                var stored by mutableIntStateOf(9)
                var enabled by mutableStateOf(true)
                val saves = CopyOnWriteArrayList<Int>()
                val composedEnabled = AtomicReference<Boolean?>(null)
                scenario.onActivity { activity ->
                    ui.configureSystemBars(activity, dark)
                    activity.setContent {
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
                                    TerminalFontPreference(stored, enabled) {
                                        traceFont("font-save enabled=$enabled old=$stored new=$it priorSaves=$saves")
                                        saves += it; stored = it
                                    }
                                    SideEffect { composedEnabled.set(enabled) }
                                }
                            }
                        }
                    }
                }
                ui.await("Font menu preference mounted") { composedEnabled.get() == true && nodes { all ->
                    fontRow(all) != null && all.none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
                } }
                ui.click(R.string.terminal_font)
                ui.await("Initial size is selected in real menu") { nodes { all ->
                    fontOption(all, 9)?.config?.getOrNull(SemanticsProperties.Selected) == true
                } }
                ui.capture("miuix-controls/font-menu-${if (dark) "night" else "day"}")
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                ui.await("Cancel font menu") { nodes { all -> fontOption(all, 9) == null } }
                assertEquals("Cancel does not persist a value", emptyList<Int>(), saves.toList())
                assertEquals(9, stored)
                for (size in listOf(28, 9, 18)) {
                    ui.click(R.string.terminal_font)
                    ui.await("Native font menu opened") { nodes { all ->
                        all.any { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                            it.config.contains(SemanticsProperties.Selected) }
                    } }
                    chooseFont(size)
                    val expected = when (size) { 28 -> listOf(28); 9 -> listOf(28, 9); else -> listOf(28, 9, 18) }
                    try {
                        ui.await("Selected size persists once: $size") { stored == size && saves.toList() == expected &&
                            nodes { all -> fontOption(all, size) == null } }
                    } catch (failure: IllegalStateException) {
                        val state = nodes { all -> fontState(all) }
                        ui.capture("miuix-controls/font-failed-${if (dark) "night" else "day"}-$size")
                        val details = "expected=$expected stored=$stored saves=$saves enabled=$enabled menu=[$state]"
                        traceFont("selection failed $details")
                        Log.e("ZakoUiTest", "font-selection failed $details", failure)
                        throw IllegalStateException("${failure.message}; $details", failure)
                    }
                }
                ui.capture("miuix-controls/font-${if (dark) "night" else "day"}")
                // Miuix removes the click action when disabled without publishing
                // Disabled semantics. Retain the real row geometry before disabling.
                val bounds = nodes { all -> screenTouchBounds(requireNotNull(fontRow(all))) }
                val density = context.resources.displayMetrics.density
                assertTrue("Font row retains 48dp target", bounds.height / density >= 47.5f)
                instrumentation.runOnMainSync { enabled = false }
                ui.await("Busy preference receives disabled state") { composedEnabled.get() == false }
                val before = saves.toList()
                traceFont("disabled touch dark=$dark enabled=$enabled stored=$stored saves=$before bounds=$bounds")
                tap(bounds)
                SystemClock.sleep(350)
                assertEquals("Disabled touch cannot persist", before, saves.toList())
                assertEquals(18, stored)
                assertTrue("Disabled touch cannot open font options", nodes { all -> (9..28).none {
                    fontOption(all, it) != null
                } })
                traceFont("theme passed dark=$dark stored=$stored saves=$saves disabledClickIgnored=true")
            }
        }
    }

    @Test(timeout = 120_000) fun actionMenuCancelDisabledAndDeferredSelectionInBothThemes() {
        val open = context.getString(R.string.ai_more)
        val disabled = context.getString(R.string.editor_undo)
        val selected = context.getString(R.string.editor_find)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                var expanded by mutableStateOf(false)
                val calls = AtomicInteger()
                val disabledCalls = AtomicInteger()
                val callsAtDismiss = CopyOnWriteArrayList<Int>()
                val mounted = AtomicReference(false)
                scenario.onActivity { activity ->
                    ui.configureSystemBars(activity, dark)
                    activity.setContent {
                        WorkbenchMiuixTheme(dark) {
                            SideEffect { mounted.set(true) }
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
                                    Box {
                                        WorkbenchIconButton(onClick = { expanded = true }) {
                                            WorkbenchIcon(Glyph.More, description = open)
                                        }
                                        WorkbenchActionMenu(expanded, onDismissRequest = {
                                            callsAtDismiss += calls.get()
                                            expanded = false
                                        }, items = listOf(
                                            DropdownItem(text = disabled, enabled = false, onClick = { disabledCalls.incrementAndGet() }),
                                            DropdownItem(text = selected, onClick = { calls.incrementAndGet() }),
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
                ui.await("Action menu fixture mounted") { mounted.get() && ui.has(open) }
                ui.click(open)
                ui.await("Action popup visible") { ui.has(selected) && ui.has(disabled) }
                ui.capture("miuix-controls/menu-${if (dark) "night" else "day"}")
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                ui.await("Cancel completes popup exit") { !ui.has(selected) && !ui.has(disabled) }
                assertEquals("Cancel cannot execute an action", 0, calls.get())
                assertEquals(0, disabledCalls.get())
                ui.click(open)
                ui.await("Reopened action popup") { ui.has(selected) && ui.has(disabled) }
                // Let the popup finish entering before injecting a physical disabled-item touch.
                SystemClock.sleep(350)
                val disabledBounds = nodes { all -> all.last {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == disabled } == true
                }.boundsInWindow }
                tap(disabledBounds)
                SystemClock.sleep(250)
                assertEquals("Physical disabled-item touch cannot invoke callback", 0, disabledCalls.get())
                assertEquals(0, calls.get())
                assertTrue("Disabled touch keeps popup open", ui.has(selected))
                callsAtDismiss.clear()
                ui.click(selected)
                ui.await("Selected action runs after popup exit") { calls.get() == 1 && !ui.has(selected) }
                assertTrue("Selection first requests dismissal", callsAtDismiss.isNotEmpty())
                assertTrue("Callback must be deferred beyond every dismissal request", callsAtDismiss.all { it == 0 })
                SystemClock.sleep(400)
                assertEquals("Exactly one selected callback", 1, calls.get())
                assertEquals(0, disabledCalls.get())
                ui.click(open)
                ui.await("Menu can reopen after selection") { ui.has(selected) }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                ui.await("Second cancel completes") { !ui.has(selected) }
                assertEquals("Reopening or cancelling cannot replay pending action", 1, calls.get())
            }
        }
    }

    private fun textLayouts(label: String): List<TextLayoutResult> = nodes { all ->
        buildList {
            all.filter { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }.forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                addAll(layouts)
            }
        }
    }
    private fun assertLaidOutFont(label: String, expectedSp: Int) {
        ui.await("Actual text layout: $label") { textLayouts(label).isNotEmpty() }
        textLayouts(label).forEach { layout ->
            assertEquals("Rendered font for $label", expectedSp.sp, layout.layoutInput.style.fontSize)
        }
    }
    private data class TouchGeometry(val id: Int, val bounds: Rect, val width: Int, val height: Int)
    private fun assertTouchTarget(label: String, density: Float) {
        fun geometry(): TouchGeometry? = nodes { all ->
            val action = all.asReversed().firstNotNullOfOrNull { node ->
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) {
                    generateSequence(node) { it.parent }.firstOrNull {
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                } else null
            }
            action?.let { TouchGeometry(it.id, it.boundsInWindow, it.size.width, it.size.height) }
        }
        var previous = geometry()
        Log.i("ZakoUiTest", "font-target initial label=$label density=$density geometry=$previous")
        var stationarySince = SystemClock.uptimeMillis()
        // A window menu can expose text semantics on its first scaled entrance frame.
        // Measure the final rendered hit box rather than an intermediate transform.
        ui.await("Stable native touch target: $label") {
            val current = geometry()
            if (current == null || current != previous) stationarySince = SystemClock.uptimeMillis()
            previous = current
            current != null && current.bounds.width > 0 && current.bounds.height > 0 &&
                SystemClock.uptimeMillis() - stationarySince >= 350
        }
        val control = requireNotNull(previous) { "Actionable native row: $label" }
        val widthDp = control.bounds.width / density
        val heightDp = control.bounds.height / density
        Log.i("ZakoUiTest", "font-target settled label=$label density=$density geometry=$control " +
            "visibleDp=${widthDp}x$heightDp layoutDp=${control.width / density}x${control.height / density}")
        assertTrue("48dp touch width: $label; geometry=$control density=$density actualDp=$widthDp", widthDp >= 47.5f)
        assertTrue("48dp touch height: $label; geometry=$control density=$density actualDp=$heightDp", heightDp >= 47.5f)
    }

    @Test(timeout = 120_000) fun nativePreferenceAndWindowFontsStayCompactInBothThemes() {
        val arrowTitle = context.getString(R.string.app_permissions_title)
        val arrowSummary = context.getString(R.string.app_permissions_hint)
        val switchTitle = context.getString(R.string.virtual_screen_title)
        val switchSummary = context.getString(R.string.capability_screen_global)
        val dropdownTitle = context.getString(R.string.terminal_shell)
        val dropdownSummary = context.getString(R.string.terminal_output_font)
        val tabs = listOf(context.getString(R.string.nav_settings), context.getString(R.string.nav_environment))
        val menu = listOf(context.getString(R.string.shell_bash), context.getString(R.string.shell_zsh))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val mounted = AtomicReference(false)
                val density = AtomicReference(1f)
                val rowCalls = AtomicInteger()
                val menuCalls = AtomicInteger()
                var checked by mutableStateOf(false)
                var tab by mutableIntStateOf(0)
                scenario.onActivity { activity ->
                    ui.configureSystemBars(activity, dark)
                    activity.setContent {
                        val deviceDensity = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(deviceDensity, 1f)) {
                            WorkbenchMiuixTheme(dark) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
                                        ArrowPreference(title = arrowTitle, summary = arrowSummary,
                                            onClick = { rowCalls.incrementAndGet() },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                        SwitchPreference(title = switchTitle, summary = switchSummary,
                                            checked = checked, onCheckedChange = { checked = it; rowCalls.incrementAndGet() },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                        WindowDropdownPreference(title = dropdownTitle, summary = dropdownSummary,
                                            items = menu, selectedIndex = 0, showValue = false,
                                            onSelectedIndexChange = { menuCalls.incrementAndGet() },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                        MiuixTabRow(tabs = tabs, selectedTabIndex = tab,
                                            onTabSelected = { tab = it }, height = 48.dp)
                                        SideEffect { density.set(deviceDensity); mounted.set(true) }
                                    }
                                }
                            }
                        }
                    }
                }
                ui.await("Native font fixture mounted") { mounted.get() && ui.has(dropdownTitle) }
                listOf(arrowTitle, switchTitle, dropdownTitle).forEach { label ->
                    assertLaidOutFont(label, 14)
                    assertTouchTarget(label, density.get())
                }
                listOf(arrowSummary, switchSummary, dropdownSummary).forEach { assertLaidOutFont(it, 12) }
                tabs.forEach { label ->
                    assertLaidOutFont(label, 14)
                    assertTouchTarget(label, density.get())
                }
                val mode = if (dark) "night" else "day"
                ui.capture("miuix-fonts/preferences-$mode")
                ui.click(dropdownTitle)
                ui.await("Native window menu visible") { menu.all(ui::has) }
                menu.forEach { label ->
                    assertLaidOutFont(label, 14)
                    assertTouchTarget(label, density.get())
                }
                ui.capture("miuix-fonts/window-menu-$mode")
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                ui.await("Native window menu cancelled") { menu.none(ui::has) }
                assertEquals("Cancel must not select an entry", 0, menuCalls.get())
                assertEquals("Measuring rows must not activate them", 0, rowCalls.get())
                assertFalse(checked)
                assertEquals(0, tab)
            }
        }
    }

}
