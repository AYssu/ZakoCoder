package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.device.DeviceMode
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/**
 * Default physical display density, 1.0 font scale, both themes. Mode-card Root is a presentation
 * fixture only; production bindings below always use isolated Basic preferences. No Root request,
 * model request, application launch, physical-screen observation or real user configuration read.
 */
@SdkSuppress(minSdkVersion = 30)
class DeviceRootUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()

    @Test(timeout = 180_000) fun dayModeCapabilitiesAndVirtualScreenReturn() = verify(false)
    @Test(timeout = 180_000) fun nightModeCapabilitiesAndVirtualScreenReturn() = verify(true)

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun <T> nodes(action: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            all += node
            // Retained parent pages must not be mistaken for interactive destinations.
            if (!node.config.isClearingSemantics) node.children.forEach(::collect)
        }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        action(all)
    }

    private fun has(resource: Int): Boolean = nodes { all ->
        val label = context.getString(resource)
        all.any { node -> node.boundsInWindow.height > 0 &&
            (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) }
    }

    private fun selected(resource: Int): Boolean? = nodes { all ->
        val label = context.getString(resource)
        fun radios(node: SemanticsNode): List<SemanticsNode> = buildList {
            if (node.boundsInWindow.height <= 0) return@buildList
            if (node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                node.config.contains(SemanticsProperties.Selected)) add(node)
            if (!node.config.isClearingSemantics) node.children.forEach { addAll(radios(it)) }
        }
        all.firstNotNullOfOrNull { node ->
            if (node.boundsInWindow.height <= 0 ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } != true) return@firstNotNullOfOrNull null
            // Miuix puts Selected on the row's trailing RadioButton, a sibling of its
            // title column. Find that single native control, not a Selected ancestor.
            generateSequence(node) { it.parent }.firstNotNullOfOrNull { ancestor ->
                radios(ancestor).singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected)
            }
        }
    }

    private fun switchControl(all: List<SemanticsNode>, label: String): SemanticsNode? {
        fun switches(node: SemanticsNode): List<SemanticsNode> = buildList {
            if (node.config.contains(SemanticsProperties.ToggleableState)) add(node)
            if (!node.config.isClearingSemantics) node.children.forEach { addAll(switches(it)) }
        }
        return all.firstNotNullOfOrNull { title ->
            if (title.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } != true) return@firstNotNullOfOrNull null
            generateSequence(title) { it.parent }.firstNotNullOfOrNull { ancestor -> switches(ancestor).singleOrNull() }
        }
    }

    private fun switchState(resource: Int): ToggleableState? = nodes { all ->
        switchControl(all, context.getString(resource))?.config?.getOrNull(SemanticsProperties.ToggleableState)
    }

    /** Miuix keeps its native switch beside the title; touch that control after scrolling settles. */
    private fun tapSwitch(resource: Int) {
        val label = context.getString(resource)
        ui.reveal(label)
        var previous: Rect? = null
        var settled = SystemClock.uptimeMillis()
        var target: Rect? = null
        ui.await("Visible, stable native switch: $label") {
            val bounds = nodes { all ->
                val control = switchControl(all, label) ?: return@nodes null
                if (control.config.contains(SemanticsProperties.Disabled)) return@nodes null
                val visible = control.boundsInWindow
                if (visible.width >= control.size.width * .95f && visible.height >= control.size.height * .95f &&
                    visible.width > 0 && visible.height > 0) visible
                else {
                    val scroll = generateSequence(control.parent) { it.parent }.firstOrNull {
                        it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                    } ?: error("No scroll ancestor for native switch: $label")
                    val centerY = control.layoutInfo.coordinates.localToWindow(Offset.Zero).y + control.size.height / 2f
                    assertTrue("Reveal native switch: $label", scroll.config[SemanticsActions.ScrollBy].action
                        ?.invoke(0f, centerY - scroll.boundsInWindow.center.y) == true)
                    null
                }
            }
            if (bounds == null || bounds != previous) settled = SystemClock.uptimeMillis()
            previous = bounds
            target = bounds
            bounds != null && SystemClock.uptimeMillis() - settled >= 350
        }
        val center = requireNotNull(target).center
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, center.x, center.y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue("Touch native switch: $label", instrumentation.uiAutomation.injectInputEvent(down, true))
            SystemClock.sleep(80)
            val up = MotionEvent.obtain(started, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center.x, center.y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true)) } finally { up.recycle() }
        } finally { down.recycle() }
    }

    private fun capture(theme: String, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(context.cacheDir, "ui-review/device-root/$theme-$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { screenshot.recycle() }
    }

    private fun verify(dark: Boolean) {
        warmDeviceActivity()
        val token = "device-root-ui-${UUID.randomUUID()}"
        val root = File(context.cacheDir, token).apply { mkdirs() }
        val preferences = ConcurrentHashMap.newKeySet<String>()
        val base = context
        val isolated = object : ContextWrapper(base) {
            // Controllers retain applicationContext: this override is required for real isolation.
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(
                "$token-$name".also(preferences::add), mode)
            override fun stopService(service: android.content.Intent) = false
        }
        isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
        var host: Workbench? = null
        val theme = if (dark) "night" else "day"
        try {
            host = main { Workbench(isolated) }
            val workbench = requireNotNull(host)
            ui.await("Isolated Basic controllers loaded") {
                !workbench.state.value.loading && workbench.deviceAccess.state.value.loaded &&
                    workbench.capabilityPackages.state.value.loaded
            }
            assertEquals(DeviceMode.Basic, workbench.deviceAccess.state.value.mode)
            assertFalse(workbench.deviceAccess.state.value.rootGranted)
            var page by mutableStateOf("mode")
            var presentedRoot by mutableStateOf(false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ui.configureSystemBars(activity, dark)
                    activity.setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                            fontScale = 1f
                        }
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density, 1f)) {
                            WorkbenchMiuixTheme(dark) {
                                when (page) {
                                    "mode" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Text(context.getString(R.string.app_permissions_title), style = MaterialTheme.typography.titleMedium)
                                            DeviceModePanel(rootMode = presentedRoot, rootGranted = presentedRoot, busy = false,
                                                error = null, onBasic = { presentedRoot = false }, onRoot = { presentedRoot = true })
                                        }
                                    }
                                    "capabilities" -> CapabilityPackageBindings(workbench, projectId = null, projectName = "",
                                        fullPage = true, contentPadding = WindowInsets.systemBars.asPaddingValues(), onDismiss = {})
                                    "virtual" -> VirtualScreenPage(workbench) { page = "capabilities" }
                                }
                            }
                        }
                    }
                }
                ui.await("Basic mode card") {
                    selected(R.string.device_mode_basic) == true && selected(R.string.device_mode_root) == false
                }
                capture(theme, "mode-basic")
                ui.click(R.string.device_mode_root)
                ui.await("Root presentation selected") {
                    selected(R.string.device_mode_root) == true && selected(R.string.device_mode_basic) == false
                }
                capture(theme, "mode-root-presentation")
                assertEquals("Presentation never enables actual Root", DeviceMode.Basic, workbench.deviceAccess.state.value.mode)
                ui.click(R.string.device_mode_basic)
                ui.await("Basic presentation restored") {
                    selected(R.string.device_mode_basic) == true && selected(R.string.device_mode_root) == false
                }

                main { page = "capabilities" }
                ui.await("Device category") { has(R.string.capability_category_device) }
                capture(theme, "capabilities-basic")
                assertEquals(ToggleableState.Off, switchState(R.string.capability_device_shell_title))
                tapSwitch(R.string.capability_device_shell_title)
                ui.await("Basic application-UID shell enabled") {
                    workbench.capabilityPackages.state.value.deviceShellEnabled && !workbench.capabilityPackages.state.value.busy &&
                        switchState(R.string.capability_device_shell_title) == ToggleableState.On
                }
                assertEquals(DeviceMode.Basic, workbench.deviceAccess.state.value.mode)
                assertTrue(workbench.capabilityPackages.enabled(null, CapabilityPackage.DeviceShell))
                capture(theme, "capabilities-basic-shell-enabled")

                ui.reveal(context.getString(R.string.virtual_screen_title))
                assertEquals(ToggleableState.Off, switchState(R.string.virtual_screen_title))
                tapSwitch(R.string.virtual_screen_title)
                ui.await("Virtual screen gate opens global permissions") { has(R.string.device_mode_basic) }
                assertFalse("Basic cannot silently enable virtual screen", workbench.capabilityPackages.state.value.screenEnabled)
                assertFalse(workbench.virtualScreen.state.value.active)
                capture(theme, "capability-root-gate-permissions")
                ui.click(R.string.close)
                ui.await("Return to retained capability list") { !has(R.string.device_mode_basic) && has(R.string.virtual_screen_title) }
                ui.scrollTop()
                assertTrue("Parent selection retained", workbench.capabilityPackages.state.value.deviceShellEnabled)
                assertEquals("Rendered parent selection retained", ToggleableState.On,
                    switchState(R.string.capability_device_shell_title))
                capture(theme, "capability-permissions-return")
                ui.reveal(context.getString(R.string.capability_category_web))
                capture(theme, "capabilities-web")
                ui.reveal(context.getString(R.string.capability_ssh_workspace_required))
                assertTrue(has(R.string.capability_category_remote))
                capture(theme, "capabilities-remote")

                main { page = "virtual" }
                ui.await("Root unavailable virtual-display empty state") { has(R.string.virtual_screen_root_required) }
                assertFalse(workbench.virtualScreen.state.value.active)
                capture(theme, "virtual-root-required-empty")
                ui.click(R.string.device_permissions_open)
                ui.await("Virtual screen permissions destination") { has(R.string.device_mode_basic) }
                capture(theme, "virtual-permissions")
                ui.click(R.string.close)
                ui.await("Permissions return to virtual screen, not chat") { !has(R.string.device_mode_basic) && has(R.string.virtual_screen_root_required) }
                capture(theme, "virtual-permissions-return")
                ui.click(R.string.close)
                ui.await("Virtual screen returns to capability parent") { has(R.string.capability_category_device) && !has(R.string.virtual_screen_root_required) }
                capture(theme, "virtual-parent-return")
            }
        } finally {
            main { host?.stopAll(); host?.virtualScreen?.close(); host?.browser?.close() }
            root.deleteRecursively()
            preferences.forEach(base::deleteSharedPreferences)
        }
    }
}
