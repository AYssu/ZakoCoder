package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.capabilities.CapabilityPackages
import dev.zakocoder.app.device.DeviceMode
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Production bindings and storage, with isolated preferences and no device/host authorization. */
class CapabilitySelectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()

    /** Match the title first, then its nearest preference row containing one real toggle. */
    private fun switchControl(all: List<SemanticsNode>, label: String): SemanticsNode? {
        fun controls(node: SemanticsNode): List<SemanticsNode> = buildList {
            // Disabled native switches can retain state while removing OnClick.
            if (node.config.getOrNull(SemanticsProperties.ToggleableState) != null) add(node)
            node.children.forEach { addAll(controls(it)) }
        }
        return all.asSequence().filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true ||
                label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.firstNotNullOfOrNull { title ->
            generateSequence(title) { it.parent }.firstNotNullOfOrNull { controls(it).singleOrNull() }
        }
    }
    private fun switchState(label: String): ToggleableState? {
        val result = AtomicReference<ToggleableState?>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(switchControl(all, label)?.config?.getOrNull(SemanticsProperties.ToggleableState))
        }
        return result.get()
    }

    private fun controlEnabled(label: String): Boolean? {
        val result = AtomicReference<Boolean?>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            // Navigation rows retain their own action during unrelated saves. Fall back
            // to the title-associated toggle when disabled switches remove OnClick.
            val control = all.asReversed().firstNotNullOfOrNull { node ->
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) {
                    generateSequence(node) { it.parent }.firstOrNull {
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                } else null
            } ?: switchControl(all, label)
            control?.let { result.set(it.config.getOrNull(SemanticsProperties.Disabled) == null) }
        }
        return result.get()
    }

    private fun controlDiagnostics(label: String): String {
        val result = AtomicReference("")
        instrumentation.runOnMainSync {
            val matches = mutableListOf<String>()
            fun walk(node: SemanticsNode, labelled: Boolean = false) {
                val matchesLabel = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                if (labelled || matchesLabel) {
                    matches += "bounds=${node.boundsInWindow}, parent=${node.parent?.boundsInWindow}, " +
                        "toggle=${node.config.getOrNull(SemanticsProperties.ToggleableState)}, " +
                        "disabled=${node.config.getOrNull(SemanticsProperties.Disabled)}, " +
                        "click=${node.config.getOrNull(SemanticsActions.OnClick) != null}"
                }
                node.children.forEach { walk(it, labelled || matchesLabel) }
            }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) walk(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(matches.joinToString("; "))
        }
        return result.get()
    }

    private fun tapSwitch(label: String, enabled: Boolean = true) = tapControl(label, enabled, toggle = true)

    /** Touch the actual control, after accessibility scrolling has actually settled. */
    private fun tapControl(label: String, enabled: Boolean = true, toggle: Boolean = false) {
        ui.reveal(label)
        fun bounds(reveal: Boolean = false): Rect? {
            val result = AtomicReference<Rect?>()
            instrumentation.runOnMainSync {
                val all = mutableListOf<SemanticsNode>()
                fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
                fun visit(view: android.view.View) {
                    if (!view.isShown) return
                    if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                    if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
                val target = if (toggle) switchControl(all, label) else all.asReversed().firstNotNullOfOrNull { node ->
                    if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                        label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) {
                        generateSequence(node) { it.parent }.firstOrNull {
                            it.config.getOrNull(SemanticsActions.OnClick) != null
                        }
                    } else null
                }
                if (target != null && (target.config.getOrNull(SemanticsProperties.Disabled) == null) != enabled)
                    return@runOnMainSync
                val control = target ?: return@runOnMainSync
                val visible = control.boundsInWindow
                if (visible.width > 0 && visible.height > 0 &&
                    visible.width >= control.size.width * .95f && visible.height >= control.size.height * .95f) {
                    result.set(visible)
                } else if (reveal) {
                    val scroll = generateSequence(control.parent) { it.parent }.firstOrNull {
                        it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                    } ?: error("No native control scroll ancestor: $label")
                    // The label wrapper can retain a 3px sliver while the control
                    // is completely clipped. Use the real, unclipped layout position.
                    val centerY = control.layoutInfo.coordinates.localToWindow(Offset.Zero).y + control.size.height / 2f
                    assertTrue("Scroll actual control into viewport: $label",
                        scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, centerY - scroll.boundsInWindow.center.y) == true)
                }
            }
            return result.get()
        }
        for (attempt in 0 until 12) {
            if (bounds(reveal = true) != null) break
            SystemClock.sleep(250)
        }
        var previous: Rect? = null
        var stationarySince = SystemClock.uptimeMillis()
        var target: Rect? = null
        val samples = ArrayDeque<String>()
        var missing = 0
        var changed = 0
        try {
            ui.await("Stable native control: $label") {
                val current = bounds()
                if (current == null) missing++
                if (current != previous) changed++
                if (current == null || current != previous) stationarySince = SystemClock.uptimeMillis()
                previous = current
                target = current
                samples.addLast("${SystemClock.uptimeMillis()}:$current")
                if (samples.size > 8) samples.removeFirst()
                current != null && SystemClock.uptimeMillis() - stationarySince >= 350
            }
        } catch (failure: IllegalStateException) {
            ui.capture("capability-selection/failed-stable-native-control")
            error("${failure.message}; missing=$missing, changed=$changed, recent=$samples; control=[${controlDiagnostics(label)}]")
        }
        // Text or a decorative thumb is not the actionable control's hit box.
        // Keep real input rather than invoking the semantics OnClick action.
        val hitBounds = requireNotNull(target)
        val center = hitBounds.center
        instrumentation.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }.forEach { window ->
                val screen = IntArray(2).also(window::getLocationOnScreen)
                val local = IntArray(2).also(window::getLocationInWindow)
                Log.i("ZakoUiTest", "tap label=$label bounds=$hitBounds size=${window.width}x${window.height} " +
                    "screenOffset=${screen.toList()} windowOffset=${local.toList()} " +
                    "screenMinusWindow=${screen[0] - local[0]},${screen[1] - local[1]}")
            }
        }
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, center.x, center.y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            Log.i("ZakoUiTest", "DOWN label=$label x=${center.x} y=${center.y} time=$time source=${down.source}")
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            SystemClock.sleep(80)
            val up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center.x, center.y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                Log.i("ZakoUiTest", "UP label=$label x=${center.x} y=${center.y} time=${up.eventTime} source=${up.source}")
                assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
            } finally { up.recycle() }
        } finally { down.recycle() }
    }

    @Test(timeout = 90_000) fun lightConfigurationButtonsStayAvailableDuringOtherPackageSaves() = verifyConfigurationButtons(false)
    @Test(timeout = 90_000) fun darkConfigurationButtonsStayAvailableDuringOtherPackageSaves() = verifyConfigurationButtons(true)

    private fun verifyConfigurationButtons(dark: Boolean) {
        warmDeviceActivity()
        // Root availability is presentation-only here: no controller or shell is authorized.
        var model by mutableStateOf(CapabilityPackagesUiModel(hasProject = true, screenEnabled = true, rootReady = true))
        val details = java.util.concurrent.atomic.AtomicInteger()
        val screenConfigure = context.getString(R.string.virtual_screen_configure)
        val sshConfigure = context.getString(R.string.capability_ssh_configure)
        val sshTitle = context.getString(R.string.capability_ssh_title)
        val browserTitle = context.getString(R.string.browser_title)
        val mode = if (dark) "night" else "day"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ui.configureSystemBars(activity, dark)
                activity.setContent {
                    WorkbenchMiuixTheme(dark) {
                        // Hold the save state until explicitly released, so even fast disks
                        // reproduce the enabled/disabled color flash deterministically.
                        CapabilityPackagesSheet(model, onScreenEnabled = { model = model.copy(screenEnabled = it, busy = true) },
                            onSshEnabled = { model = model.copy(sshEnabled = it, busy = true) },
                            onScreenDetails = { details.incrementAndGet() }, onSshDetails = { details.incrementAndGet() },
                            onDismiss = {}, fullPage = true,
                            onBrowserEnabled = { model = model.copy(browserEnabled = it, busy = true) },
                            contentPadding = WindowInsets.systemBars.asPaddingValues())
                    }
                }
            }
            ui.reveal(screenConfigure)
            ui.await("Virtual screen configuration ready") { controlEnabled(screenConfigure) == true }
            ui.capture("capability-buttons/$mode-idle")
            tapSwitch(sshTitle)
            ui.await("SSH save in progress") { model.busy && model.sshEnabled }
            ui.reveal(screenConfigure)
            assertEquals("Unrelated save must not disable screen configuration", true, controlEnabled(screenConfigure))
            ui.capture("capability-buttons/$mode-saving-ssh")
            tapControl(screenConfigure)
            instrumentation.waitForIdleSync()
            assertEquals(1, details.get())
            // Saves still serialize: another switch cannot mutate the pending selection.
            assertEquals(false, controlEnabled(browserTitle))
            // Exercise the actual disabled native control, even if its label or
            // wrapper is already visible while the switch itself is clipped.
            tapSwitch(browserTitle, enabled = false)
            instrumentation.waitForIdleSync()
            assertTrue("Disabled browser touch must preserve the pending SSH save", model.busy)
            assertFalse(model.browserEnabled)
            assertEquals(ToggleableState.Off, switchState(browserTitle))
            instrumentation.runOnMainSync { model = model.copy(busy = false) }
            // Wait for the switch's committed semantics, not the always-available
            // navigation button: model changes precede the next rendered frame.
            ui.await("Previous save finished") { controlEnabled(browserTitle) == true }
            tapSwitch(browserTitle)
            ui.await("Browser save in progress") { model.busy && model.browserEnabled }
            for (label in listOf(screenConfigure, sshConfigure)) {
                ui.reveal(label)
                assertEquals("Browser save must not disable $label", true, controlEnabled(label))
                val before = details.get()
                tapControl(label)
                instrumentation.waitForIdleSync()
                assertEquals("One physical touch must open $label", before + 1, details.get())
            }
            assertEquals(3, details.get())
            instrumentation.runOnMainSync { model = model.copy(busy = false) }
            ui.reveal(screenConfigure)
            ui.await("Configuration available after save") { controlEnabled(screenConfigure) == true }
            ui.capture("capability-buttons/$mode-saved")
        }
    }

    @Test(timeout = 180_000) fun independentSelectionsUpdateImmediatelyAndSurvivePageAndStoreRecreation() {
        warmDeviceActivity()
        val token = "capability-selection-${UUID.randomUUID()}"
        val root = File(context.cacheDir, token).apply { mkdirs() }
        val preferences = ConcurrentHashMap.newKeySet<String>()
        val base = context
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(
                "$token-$name".also { preferences += it }, mode)
            override fun stopService(service: android.content.Intent) = false
        }
        isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
        var host: Workbench? = null
        try {
            instrumentation.runOnMainSync { host = Workbench(isolated) }
            val workbench = requireNotNull(host)
            ui.await("Fresh Basic workbench") {
                !workbench.state.value.loading && workbench.capabilityPackages.state.value.loaded &&
                    workbench.deviceAccess.state.value.loaded
            }
            assertEquals(DeviceMode.Basic, workbench.deviceAccess.state.value.mode)
            assertFalse(workbench.deviceAccess.state.value.rootGranted)
            instrumentation.runOnMainSync { workbench.create("Capability selection fixture") }
            ui.await("Fixture workspace") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val id = workbench.state.value.projects.single().id
            var generation by mutableIntStateOf(0)
            var dark by mutableStateOf(false)
            // Explicit keys prevent a new capability or a different visual order from
            // silently pairing a switch label with the wrong persisted selection.
            val labels = linkedMapOf(
                CapabilityPackage.DeviceShell to context.getString(R.string.capability_device_shell_title),
                CapabilityPackage.Screen to context.getString(R.string.virtual_screen_title),
                CapabilityPackage.Browser to context.getString(R.string.browser_title),
                CapabilityPackage.Ssh to context.getString(R.string.capability_ssh_title),
            )
            assertEquals(CapabilityPackage.entries.toSet(), labels.keys)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    WorkbenchMiuixTheme(dark) {
                        key(generation) { CapabilityPackageBindings(workbench, id, "Capability selection fixture",
                            fullPage = true, contentPadding = WindowInsets.systemBars.asPaddingValues()) {} }
                    }
                } }
                fun assertSelection(expected: Set<CapabilityPackage>) {
                    labels.forEach { (kind, label) ->
                        ui.reveal(label)
                        ui.await("Rendered independent state: $kind = ${kind in expected}") {
                            switchState(label) == if (kind in expected) ToggleableState.On else ToggleableState.Off
                        }
                    }
                    assertEquals(expected, workbench.capabilityPackages.snapshot(id).keys)
                }
                fun tap(kind: CapabilityPackage, expectChange: Boolean = true) {
                    val label = labels.getValue(kind)
                    ui.reveal(label)
                    val previous = workbench.capabilityPackages.enabled(id, kind)
                    val before = controlDiagnostics(label)
                    tapSwitch(label)
                    ui.await("Selection saved") { !workbench.capabilityPackages.state.value.busy }
                    if (expectChange) try {
                        ui.await("Touch toggles stored $kind selection") {
                            workbench.capabilityPackages.enabled(id, kind) != previous
                        }
                    } catch (failure: IllegalStateException) {
                        val state = workbench.capabilityPackages.state.value
                        ui.capture("capability-selection/failed-$kind-touch")
                        error("$kind touch did not toggle stored selection; before=$previous, " +
                            "stored=${workbench.capabilityPackages.snapshot(id).keys}, busy=${state.busy}, error=${state.error}; " +
                            "before control=[$before]; after control=[${controlDiagnostics(label)}]; ${failure.message}")
                    }
                }
                assertSelection(emptySet())

                // Production Basic bindings must open permissions rather than granting
                // virtual-screen availability merely because its switch was touched.
                tap(CapabilityPackage.Screen, expectChange = false)
                ui.await("Basic virtual-screen enable opens permissions") { ui.has(R.string.device_mode_basic) }
                assertFalse(workbench.capabilityPackages.state.value.screenEnabled)
                assertFalse(workbench.virtualScreen.state.value.active)
                assertEquals(DeviceMode.Basic, workbench.deviceAccess.state.value.mode)
                ui.click(R.string.close)
                ui.await("Return from Root gate retains capability page") {
                    !ui.has(R.string.device_mode_basic) && ui.has(R.string.virtual_screen_title)
                }
                assertSelection(emptySet())

                // Seed only the stored switch to cover presenting and disabling an
                // existing preference on a non-Root emulator. This is neither a Root
                // grant nor a virtual-display/task authorization or execution fixture.
                instrumentation.runOnMainSync { workbench.capabilityPackages.setScreenEnabled(true) }
                ui.await("Seeded screen selection saved") { !workbench.capabilityPackages.state.value.busy }
                ui.reveal(labels.getValue(CapabilityPackage.Screen))
                // Before the original fix the store became true while this switch stayed off.
                ui.await("Screen switch must immediately reflect saved selection") {
                    switchState(labels.getValue(CapabilityPackage.Screen)) == ToggleableState.On
                }
                assertSelection(setOf(CapabilityPackage.Screen))
                tap(CapabilityPackage.DeviceShell)
                assertSelection(setOf(CapabilityPackage.Screen, CapabilityPackage.DeviceShell))
                tap(CapabilityPackage.Ssh)
                assertSelection(setOf(CapabilityPackage.Screen, CapabilityPackage.DeviceShell, CapabilityPackage.Ssh))
                tap(CapabilityPackage.Screen)
                tap(CapabilityPackage.Ssh)
                tap(CapabilityPackage.DeviceShell)
                tap(CapabilityPackage.Browser)
                assertSelection(setOf(CapabilityPackage.Browser))
                for (night in listOf(false, true)) {
                    scenario.onActivity { ui.configureSystemBars(it, night) }
                    instrumentation.runOnMainSync { dark = night; generation++ }
                    SystemClock.sleep(350)
                    assertSelection(setOf(CapabilityPackage.Browser))
                    ui.scrollTop()
                    ui.capture("capability-selection/${if (night) "night" else "day"}-browser-only-top")
                    ui.reveal(labels.getValue(CapabilityPackage.Browser))
                    ui.capture("capability-selection/${if (night) "night" else "day"}-browser-only-bottom")
                }
                lateinit var reloaded: CapabilityPackages
                instrumentation.runOnMainSync { reloaded = CapabilityPackages(isolated) }
                ui.await("Store reloaded") { reloaded.state.value.loaded }
                assertEquals(setOf(CapabilityPackage.Browser), reloaded.snapshot(id).keys)
                assertFalse(reloaded.enabled(id, CapabilityPackage.Screen))
                assertFalse(reloaded.enabled(id, CapabilityPackage.Ssh))
                assertFalse(reloaded.enabled(id, CapabilityPackage.DeviceShell))
                // Disable browser, then re-enable and disable each other capability independently.
                tap(CapabilityPackage.Browser)
                for (kind in labels.keys.filter { it != CapabilityPackage.Screen }) {
                    tap(kind); assertSelection(setOf(kind))
                    tap(kind); assertSelection(emptySet())
                }
            }
        } finally {
            instrumentation.runOnMainSync { host?.stopAll(); host?.browser?.close() }
            root.deleteRecursively()
            preferences.forEach(context::deleteSharedPreferences)
        }
    }
}
