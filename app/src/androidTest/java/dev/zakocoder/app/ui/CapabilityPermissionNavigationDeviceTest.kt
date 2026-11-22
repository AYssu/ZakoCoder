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

/** Real app routes, isolated settings, no permission grants, root requests or workspace changes. */
@SdkSuppress(minSdkVersion = 30)
class CapabilityPermissionNavigationDeviceTest {
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
        val directory = File(base.cacheDir, "ui-review/capability-permissions").apply { mkdirs() }
        File(directory, "${if (dark) "night" else "day"}-$route-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    /** Dispatch into the window that actually contains the unique device-mode control. */
    private fun permissionBack(scenario: ActivityScenario<MainActivity>): PermissionBack {
        val activityRoot = AtomicReference<View>()
        scenario.onActivity { activityRoot.set(it.window.decorView.rootView) }
        return main {
            var foreground: View? = null
            fun hasPermissionMarker(node: SemanticsNode): Boolean {
                if (visible(node) && matches(node, R.string.device_mode_basic)) return true
                return !node.config.isClearingSemantics && node.children.any(::hasPermissionMarker)
            }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest && hasPermissionMarker(view.semanticsOwner.unmergedRootSemanticsNode)) foreground = view
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
            val owner = requireNotNull(foreground) { "Foreground permission window" }
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
        assertTrue("Gesture must expose the actual capability background ($equal/$total match)", equal >= total * .95)
    }

    private fun isolated(block: (Workbench) -> Unit) = runBlocking {
        warmDeviceActivity()
        val token = "capability-navigation-${UUID.randomUUID()}"
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

    @Test(timeout = 180_000)
    fun drawerCapabilitiesPermissionChildRetainsParentInBothThemes() = checkRoute(fromSettings = false)

    @Test(timeout = 180_000)
    fun settingsCapabilitiesPermissionChildRetainsParentInBothThemes() = checkRoute(fromSettings = true)

    private fun checkRoute(fromSettings: Boolean) = isolated { host ->
        val route = if (fromSettings) "settings" else "drawer"
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var stage = "root"
            try {
                val mountedRoot = "capability-permission-root-${UUID.randomUUID()}"
                scenario.onActivity { activity -> activity.setContent {
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration) {
                        Box(Modifier.fillMaxSize().testTag(mountedRoot)) { ZakoCoderApp(host) }
                    }
                } }
                // setContent schedules recomposition; the Activity's previous app tree can
                // still expose the same navigation label until this isolated root commits.
                await("Isolated root mounted") { nodes { all -> all.any {
                    visible(it) && it.config.getOrNull(SemanticsProperties.TestTag) == mountedRoot
                } } }
                await("Root ready") { shown(R.string.app_navigation_open) }
                settle()
                click(R.string.app_navigation_open)
                await("Drawer ready") { shown(R.string.conversation_search_title) }
                if (fromSettings) {
                    click(R.string.nav_settings)
                    await("Settings ready") { shown(R.string.ai_settings) }
                    revealSettingsCapabilities()
                }
                click(R.string.capability_packages_title)
                stage = "capabilities"
                await("Capability permission action ready") { shown(R.string.device_permissions_open) }
                settle()
                val reference = capture(dark, route, "capabilities-before")
                val initialWindows = windowCount()
                try {
                    repeat(2) { attempt ->
                        stage = "permissions-$attempt"
                        click(R.string.device_permissions_open)
                        await("Permission page must remain visible above its parent") {
                            shown(R.string.app_permissions_title) && shown(R.string.device_mode_basic)
                        }
                        settle()
                        val childWindows = windowCount()
                        if (!fromSettings) assertEquals("Full-page capability child shares its original window", initialWindows, childWindows)
                        else assertTrue("Sheet capability permissions must open above the retained sheet", childWindows > initialWindows)
                        permissionBack(scenario).use { back ->
                            val restingLeft = left(R.string.device_mode_basic)
                            assertTrue("Permission page starts at its full-width resting position", restingLeft < base.resources.displayMetrics.widthPixels / 2f)
                            capture(dark, route, "permissions-$attempt").recycle()
                            stage = "progress-$attempt"
                            progress(back)
                            assertTrue("Permission page tracks the in-progress gesture", left(R.string.device_mode_basic) > restingLeft + 12f)
                            capture(dark, route, "progress-$attempt").let { actual ->
                                try { assertParentEdge(reference, actual) } finally { actual.recycle() }
                            }
                            back.cancelled()
                            settle()
                            assertTrue("Cancelled gesture preserves the permission page", shown(R.string.device_mode_basic))
                            assertEquals("Cancellation returns to the same full-page position", restingLeft, left(R.string.device_mode_basic), 1.5f)
                            assertEquals("Cancellation must not create another window", childWindows, windowCount())
                            capture(dark, route, "cancelled-$attempt").recycle()
                            stage = "commit-$attempt"
                            progress(back)
                            back.completed()
                            await("Permission Back restores capability parent") {
                                !shown(R.string.device_mode_basic) && shown(R.string.device_permissions_open)
                            }
                            settle()
                            assertEquals("Child Back restores its original window stack", initialWindows, windowCount())
                            capture(dark, route, "restored-$attempt").let { actual ->
                                try { assertParentEdge(reference, actual) } finally { actual.recycle() }
                            }
                            assertEquals(Lifecycle.State.RESUMED, scenario.state)
                        }
                    }
                    stage = "capabilities-back"
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Capability Back restores actual source") {
                        if (fromSettings) shown(R.string.capability_packages_settings_hint) && !shown(R.string.capability_device_shell_title)
                        else shown(R.string.conversation_search_title)
                    }
                    assertTrue("No fixture workspace created", host.state.value.projects.isEmpty())
                    assertTrue("No model task started", !host.ai.state.value.active)
                } finally { reference.recycle() }
            } catch (failure: Throwable) {
                runCatching { capture(dark, route, "failure-$stage").recycle() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
