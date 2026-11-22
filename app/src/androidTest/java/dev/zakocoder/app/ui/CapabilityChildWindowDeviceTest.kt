package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.OnboardingProgress
import dev.zakocoder.app.settings.OnboardingRepository
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Window ownership regressions: real routes, isolated settings, no Root or runtime installation. */
@SdkSuppress(minSdkVersion = 30)
class CapabilityChildWindowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
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

    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("\n") { it.text }.orEmpty()

    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        val display = base.resources.displayMetrics
        return bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < display.widthPixels && bounds.top < display.heightPixels
    }

    private fun matches(node: SemanticsNode, resource: Int): Boolean {
        val text = base.getString(resource)
        return label(node) == text || text in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun shown(resource: Int) = nodes { all -> all.any { visible(it) && matches(it, resource) } }
    private fun left(resource: Int) = nodes { all -> all.last { visible(it) && matches(it, resource) }.boundsInWindow.left }
    private fun windowCount() = main { WindowInspector.getGlobalWindowViews().count { it.isShown } }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) {
                "$message; visible=" + nodes { all -> all.filter(::visible).map(::label).filter(String::isNotEmpty).distinct() }
            }
            Thread.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }

    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }

    private fun click(resource: Int) {
        fun enabled(node: SemanticsNode) = visible(node) && generateSequence(node) { it.parent }.none {
            it.config.getOrNull(SemanticsProperties.Disabled) != null
        }
        fun action(node: SemanticsNode): (() -> Boolean)? {
            if (!enabled(node)) return null
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            return node.children.firstNotNullOfOrNull(::action)
        }
        await("Enabled action missing: ${base.getString(resource)}") {
            nodes { all ->
                val click = all.asReversed().asSequence().filter { visible(it) && matches(it, resource) }
                    .firstNotNullOfOrNull { node -> action(node) ?: generateSequence(node.parent) { it.parent }
                        .firstNotNullOfOrNull { parent -> if (enabled(parent)) parent.config.getOrNull(SemanticsActions.OnClick)?.action else null } }
                    ?: return@nodes false
                assertTrue("Action accepted: ${base.getString(resource)}", click())
                true
            }
        }
    }

    private fun revealSettingsCapabilities() {
        repeat(8) {
            if (shown(R.string.capability_packages_settings_hint)) return
            nodes { all ->
                val list = all.last { visible(it) && it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
                assertTrue("Settings scroll accepts navigation", list.config[SemanticsActions.ScrollBy].action?.invoke(0f, 420f) == true)
            }
            settle()
        }
        error("Capabilities row was not reachable in settings")
    }

    private fun capture(dark: Boolean, route: String, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(base.cacheDir, "ui-review/capability-child-windows").apply { mkdirs() }
        File(directory, "${if (dark) "night" else "day"}-$route-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    /** Dispatch into the actual foreground window that owns this destination marker. */
    private fun pageBack(scenario: ActivityScenario<MainActivity>, marker: Int): PermissionBack {
        val activityRoot = AtomicReference<View>()
        scenario.onActivity { activityRoot.set(it.window.decorView.rootView) }
        return main {
            var foreground: View? = null
            fun hasPermissionMarker(node: SemanticsNode): Boolean {
                if (visible(node) && matches(node, marker)) return true
                return !node.config.isClearingSemantics && node.children.any(::hasPermissionMarker)
            }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest && hasPermissionMarker(view.semanticsOwner.unmergedRootSemanticsNode)) foreground = view
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
            val owner = requireNotNull(foreground) { "Foreground window for ${base.getString(marker)}" }
            val inActivity = owner.rootView === activityRoot.get()
            val dispatcher = if (inActivity) null else requireNotNull(owner.findViewTreeNavigationEventDispatcherOwner())
                .navigationEventDispatcher
            PermissionBack(scenario, dispatcher).also { it.attach() }
        }
    }

    private inner class PermissionBack(
        private val scenario: ActivityScenario<MainActivity>,
        private val dialogDispatcher: NavigationEventDispatcher?
    ) : java.io.Closeable {
        private val input = DirectNavigationEventInput()
        fun attach() { dialogDispatcher?.addInput(input) }
        fun started() {
            if (dialogDispatcher == null) scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
            } else main { input.backStarted(NavigationEvent(touchY = 600f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        }
        fun progressed() {
            if (dialogDispatcher == null) scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 600f, .65f, BackEventCompat.EDGE_LEFT))
            } else main { input.backProgressed(NavigationEvent(touchY = 600f, progress = .65f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        }
        fun cancelled() {
            if (dialogDispatcher == null) scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            else main { input.backCancelled() }
        }
        fun completed() {
            if (dialogDispatcher == null) scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            else main { input.backCompleted() }
        }
        override fun close() { main { dialogDispatcher?.removeInput(input) }; Unit }
    }

    private fun progress(back: PermissionBack) {
        back.started()
        Thread.sleep(80)
        back.progressed()
        Thread.sleep(150)
    }

    private fun assertParentEdge(reference: Bitmap, actual: Bitmap) {
        assertEquals(reference.width, actual.width)
        assertEquals(reference.height, actual.height)
        var equal = 0
        var total = 0
        // Parent pixels must remain stable, without a dim scrim, intermediate chat page,
        // or newly laid-out collapsed capability list. Ignore rounded corners/system bars.
        for (y in reference.height / 5 until reference.height * 4 / 5 step 17) {
            for (x in 3..12 step 3) {
                total++
                if (reference.getPixel(x, y) == actual.getPixel(x, y)) equal++
            }
        }
        assertTrue("Gesture must expose its actual retained parent background ($equal/$total match)", equal >= total * .95)
    }

    private fun isolated(block: (Workbench) -> Unit) = runBlocking {
        warmDeviceActivity()
        val token = "capability-child-window-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, token).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val original = base
        val isolated = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences(
                "$token-$name".also { synchronized(names) { names += it } }, mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var host: Workbench? = null
        try {
            withContext(Dispatchers.IO) {
                assertTrue(isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit())
                OnboardingRepository(isolated).save(OnboardingProgress(completed = true, environmentConfirmed = false))
            }
            val workbench = main { Workbench(isolated) }.also { host = it }
            await("Isolated workbench initialized") {
                !workbench.state.value.loading && !workbench.ai.models.state.value.loading &&
                    workbench.capabilityPackages.state.value.loaded && workbench.deviceAccess.state.value.loaded
            }
            block(workbench)
        } finally {
            withContext(NonCancellable) {
                main { host?.stopAll(); host?.browser?.close() }
                delay(250)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(directory)
                    synchronized(names) { names.toList() }.forEach(original::deleteSharedPreferences)
                }
            }
        }
    }

    private fun showApp(scenario: ActivityScenario<MainActivity>, host: Workbench, dark: Boolean) {
        val mountedRoot = "capability-child-root-${UUID.randomUUID()}"
        scenario.onActivity { activity -> activity.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                Box(Modifier.fillMaxSize().testTag(mountedRoot)) { ZakoCoderApp(host) }
            }
        } }
        // Wait for this content, rather than the Activity's outgoing real Workbench.
        await("Isolated root mounted") { nodes { all -> all.any {
            visible(it) && it.config.getOrNull(SemanticsProperties.TestTag) == mountedRoot
        } } }
        await("Root ready") { shown(R.string.app_navigation_open) }
        settle()
    }

    private fun checkWindowWidth(marker: Int) {
        val width = main {
            var marked: View? = null
            fun containsMarker(node: SemanticsNode): Boolean =
                visible(node) && matches(node, marker) ||
                    !node.config.isClearingSemantics && node.children.any(::containsMarker)
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest && containsMarker(view.semanticsOwner.unmergedRootSemanticsNode)) marked = view
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
            requireNotNull(marked).rootView.width
        }
        assertEquals("Destination owns the full window width", base.resources.displayMetrics.widthPixels, width)
    }

    private fun cancelAndReturn(scenario: ActivityScenario<MainActivity>, marker: Int,
        parentMarker: Int, parentWindows: Int, reference: Bitmap, dark: Boolean, route: String, label: String) {
        pageBack(scenario, marker).use { back ->
            val initialLeft = left(marker)
            val childWindows = windowCount()
            checkWindowWidth(marker)
            capture(dark, route, "$label-before").recycle()
            progress(back)
            assertTrue("Destination follows the gesture", left(marker) > initialLeft + 12f)
            capture(dark, route, "$label-progress").let { actual ->
                try { assertParentEdge(reference, actual) } finally { actual.recycle() }
            }
            back.cancelled()
            settle()
            assertTrue("Cancellation preserves the child", shown(marker))
            assertEquals("Cancellation restores position", initialLeft, left(marker), 1.5f)
            assertEquals("Cancellation preserves its window", childWindows, windowCount())
            capture(dark, route, "$label-cancelled").recycle()
            progress(back)
            back.completed()
            await("Back restores the original parent") { !shown(marker) && shown(parentMarker) }
            settle()
            assertEquals("Back restores the same parent window stack", parentWindows, windowCount())
            capture(dark, route, "$label-restored").let { actual ->
                try { assertParentEdge(reference, actual) } finally { actual.recycle() }
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test(timeout = 180_000)
    fun settingsCapabilityVirtualScreenChildOwnsItsWindowInBothThemes() = checkVirtualScreenRoute(fromSettings = true)

    @Test(timeout = 180_000)
    fun drawerCapabilityVirtualScreenChildRetainsItsPageInBothThemes() = checkVirtualScreenRoute(fromSettings = false)

    private fun checkVirtualScreenRoute(fromSettings: Boolean) = isolated { host ->
        // This enables only the isolated preference, never requests Root or starts a display.
        main { host.capabilityPackages.setScreenEnabled(true) }
        await("Screen preference saved") {
            host.capabilityPackages.state.value.screenEnabled && !host.capabilityPackages.state.value.busy
        }
        val route = if (fromSettings) "settings" else "drawer"
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var stage = "root"
            try {
                showApp(scenario, host, dark)
                click(R.string.app_navigation_open)
                await("Drawer ready") { shown(R.string.conversation_search_title) }
                if (fromSettings) {
                    click(R.string.nav_settings)
                    await("Settings ready") { shown(R.string.ai_settings) }
                    revealSettingsCapabilities()
                }
                click(R.string.capability_packages_title)
                stage = "capabilities"
                await("Virtual screen configuration is reachable") { shown(R.string.virtual_screen_configure) }
                settle()
                val capabilityReference = capture(dark, route, "capabilities-before")
                try {
                    val capabilityWindows = windowCount()
                    click(R.string.virtual_screen_configure)
                    stage = "virtual-screen"
                    await("Virtual screen is visible") { shown(R.string.virtual_screen_root_required) }
                    settle()
                    val screenWindows = windowCount()
                    if (fromSettings) assertTrue("Sheet child owns a window above its retained capability sheet", screenWindows > capabilityWindows)
                    else assertEquals("Full-page child stays in the original window", capabilityWindows, screenWindows)
                    val screenReference = capture(dark, route, "virtual-screen")
                    try {
                        click(R.string.device_permissions_open)
                        stage = "permissions"
                        await("Permissions appear above virtual screen") { shown(R.string.device_mode_basic) }
                        settle()
                        if (fromSettings) assertTrue("Permission child owns a window above the screen dialog", windowCount() > screenWindows)
                        else assertEquals("Embedded permissions stay in the original window", screenWindows, windowCount())
                        cancelAndReturn(scenario, R.string.device_mode_basic, R.string.virtual_screen_root_required,
                            screenWindows, screenReference, dark, route, "permissions")
                    } finally { screenReference.recycle() }
                    stage = "screen-back"
                    cancelAndReturn(scenario, R.string.virtual_screen_root_required, R.string.virtual_screen_configure,
                        capabilityWindows, capabilityReference, dark, route, "screen")
                    stage = "capabilities-back"
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Capabilities return to their original source") {
                        if (fromSettings) shown(R.string.capability_packages_settings_hint) && !shown(R.string.capability_device_shell_title)
                        else shown(R.string.conversation_search_title)
                    }
                    assertTrue("No fixture workspace created", host.state.value.projects.isEmpty())
                    assertTrue("No AI task started", !host.ai.state.value.active)
                    assertTrue("No virtual display created", !host.virtualScreen.state.value.active)
                    assertTrue("No Root requested", !host.deviceAccess.state.value.rootGranted)
                } finally { capabilityReference.recycle() }
            } catch (failure: Throwable) {
                runCatching { capture(dark, route, "failure-$stage").recycle() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    @Test(timeout = 180_000)
    fun drawerTerminalIsOpaqueAndReturnsToSameDrawerInBothThemes() = isolated { host ->
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showApp(scenario, host, dark)
            click(R.string.app_navigation_open)
            await("Drawer ready") { shown(R.string.conversation_search_title) }
            settle()
            val reference = capture(dark, "terminal", "drawer-before")
            try {
                val windows = windowCount()
                click(R.string.nav_terminal)
                await("Terminal destination ready") { shown(R.string.terminal_empty_title) }
                settle()
                capture(dark, "terminal", "opaque").let { screenshot ->
                    try {
                        var samples = 0
                        var black = 0
                        for (y in screenshot.height / 5 until screenshot.height * 4 / 5 step 17) {
                            for (x in 3..12 step 3) {
                                samples++
                                if (screenshot.getPixel(x, y) == android.graphics.Color.BLACK) black++
                            }
                        }
                        assertTrue("Terminal body is opaque black ($black/$samples)", black >= samples * .95)
                    } finally { screenshot.recycle() }
                }
                cancelAndReturn(scenario, R.string.terminal_empty_title, R.string.conversation_search_title,
                    windows, reference, dark, "terminal", "terminal")
                assertTrue("No terminal process created", host.state.value.sessions.isEmpty())
                assertTrue("Runtime not installed by this test", !host.state.value.installed)
            } finally { reference.recycle() }
        }
    }
}
