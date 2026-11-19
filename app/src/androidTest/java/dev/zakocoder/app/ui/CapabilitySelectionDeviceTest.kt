package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.*
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
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Production bindings and storage, with isolated preferences and no device/host authorization. */
class CapabilitySelectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()

    private fun switchState(label: String): ToggleableState? {
        val result = AtomicReference<ToggleableState?>()
        instrumentation.runOnMainSync {
            fun walk(node: SemanticsNode, labelled: Boolean = false) {
                val matches = labelled || node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                if (matches) node.config.getOrNull(SemanticsProperties.ToggleableState)?.let(result::set)
                node.children.forEach { walk(it, matches) }
            }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) walk(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return result.get()
    }

    private fun controlEnabled(label: String): Boolean? {
        val result = AtomicReference<Boolean?>()
        instrumentation.runOnMainSync {
            fun actionWithin(node: SemanticsNode): SemanticsNode? =
                if (node.config.getOrNull(SemanticsActions.OnClick) != null) node
                else node.children.firstNotNullOfOrNull(::actionWithin)
            fun walk(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) {
                    var control: SemanticsNode? = actionWithin(node) ?: node
                    while (control != null && control.config.getOrNull(SemanticsActions.OnClick) == null) control = control.parent
                    control?.let { result.set(it.config.getOrNull(SemanticsProperties.Disabled) == null) }
                }
                node.children.forEach(::walk)
            }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) walk(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return result.get()
    }

    @Test(timeout = 90_000) fun lightConfigurationButtonsStayAvailableDuringOtherPackageSaves() = verifyConfigurationButtons(false)
    @Test(timeout = 90_000) fun darkConfigurationButtonsStayAvailableDuringOtherPackageSaves() = verifyConfigurationButtons(true)

    private fun verifyConfigurationButtons(dark: Boolean) {
        warmDeviceActivity()
        var model by mutableStateOf(CapabilityPackagesUiModel(hasProject = true, screenEnabled = true))
        val details = java.util.concurrent.atomic.AtomicInteger()
        val screenConfigure = context.getString(R.string.capability_screen_configure)
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
            ui.await("Screen configuration ready") { controlEnabled(screenConfigure) == true }
            ui.capture("capability-buttons/$mode-idle")
            ui.tap(sshTitle)
            ui.await("SSH save in progress") { model.busy && model.sshEnabled }
            ui.reveal(screenConfigure)
            assertEquals("Unrelated save must not disable screen configuration", true, controlEnabled(screenConfigure))
            ui.capture("capability-buttons/$mode-saving-ssh")
            ui.tap(screenConfigure)
            assertEquals(1, details.get())
            // Saves still serialize: another switch cannot mutate the pending selection.
            assertEquals(false, controlEnabled(browserTitle))
            // The browser title can be visible while its switch is still below
            // the viewport; reveal the final notice to expose the entire card.
            ui.reveal(context.getString(R.string.capability_packages_loading_hint))
            ui.tap(browserTitle)
            assertFalse(model.browserEnabled)
            instrumentation.runOnMainSync { model = model.copy(busy = false) }
            // Wait for the switch's committed semantics, not the always-available
            // navigation button: model changes precede the next rendered frame.
            ui.await("Previous save finished") { controlEnabled(browserTitle) == true }
            ui.click(browserTitle)
            ui.await("Browser save in progress") { model.busy && model.browserEnabled }
            for (label in listOf(screenConfigure, sshConfigure)) {
                ui.reveal(label)
                assertEquals("Browser save must not disable $label", true, controlEnabled(label))
                ui.tap(label)
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
        val preferences = mutableSetOf<String>()
        val base = context
        val isolated = object : ContextWrapper(base) {
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
            ui.await("Fresh workbench") { !workbench.state.value.loading && workbench.capabilityPackages.state.value.loaded }
            instrumentation.runOnMainSync { workbench.create("Capability selection fixture") }
            ui.await("Fixture workspace") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val id = workbench.state.value.projects.single().id
            var generation by mutableIntStateOf(0)
            var dark by mutableStateOf(false)
            val labels = listOf(R.string.capability_screen_title, R.string.capability_ssh_title, R.string.browser_title)
                .map(context::getString)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    WorkbenchMiuixTheme(dark) {
                        key(generation) { CapabilityPackageBindings(workbench, id, "Capability selection fixture",
                            fullPage = true, contentPadding = WindowInsets.systemBars.asPaddingValues()) {} }
                    }
                } }
                fun assertSelection(expected: Set<CapabilityPackage>) {
                    CapabilityPackage.entries.zip(labels).forEach { (kind, label) ->
                        ui.reveal(label)
                        ui.await("Rendered independent state: $kind = ${kind in expected}") {
                            switchState(label) == if (kind in expected) ToggleableState.On else ToggleableState.Off
                        }
                    }
                    assertEquals(expected, workbench.capabilityPackages.snapshot(id).keys)
                }
                fun tap(index: Int) {
                    ui.tap(labels[index])
                    ui.await("Selection saved") { !workbench.capabilityPackages.state.value.busy }
                }
                assertSelection(emptySet())
                tap(0)
                // Before the fix the store becomes true, but this first switch stays visually off.
                ui.await("Screen switch must immediately reflect saved selection") {
                    switchState(labels[0]) == ToggleableState.On
                }
                assertSelection(setOf(CapabilityPackage.Screen))
                tap(1)
                assertSelection(setOf(CapabilityPackage.Screen, CapabilityPackage.Ssh))
                tap(0); tap(1); tap(2)
                assertSelection(setOf(CapabilityPackage.Browser))
                for (night in listOf(false, true)) {
                    scenario.onActivity { ui.configureSystemBars(it, night) }
                    instrumentation.runOnMainSync { dark = night; generation++ }
                    SystemClock.sleep(350)
                    assertSelection(setOf(CapabilityPackage.Browser))
                    ui.scrollTop()
                    ui.capture("capability-selection/${if (night) "night" else "day"}-browser-only-top")
                    ui.reveal(labels[2])
                    ui.capture("capability-selection/${if (night) "night" else "day"}-browser-only-bottom")
                }
                lateinit var reloaded: CapabilityPackages
                instrumentation.runOnMainSync { reloaded = CapabilityPackages(isolated) }
                ui.await("Store reloaded") { reloaded.state.value.loaded }
                assertEquals(setOf(CapabilityPackage.Browser), reloaded.snapshot(id).keys)
                assertFalse(reloaded.enabled(id, CapabilityPackage.Screen))
                assertFalse(reloaded.enabled(id, CapabilityPackage.Ssh))
                // Disable browser, then re-enable and disable each other capability independently.
                tap(2)
                for (index in labels.indices) {
                    tap(index); assertSelection(setOf(CapabilityPackage.entries[index]))
                    tap(index); assertSelection(emptySet())
                }
            }
        } finally {
            instrumentation.runOnMainSync { host?.stopAll(); host?.browser?.close() }
            root.deleteRecursively()
            preferences.forEach(context::deleteSharedPreferences)
        }
    }
}
