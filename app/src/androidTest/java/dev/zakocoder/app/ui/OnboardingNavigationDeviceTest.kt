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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real setup/permissions pages and AndroidX back dispatch; no grants, installs or model calls. */
@SdkSuppress(minSdkVersion = 30)
class OnboardingNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
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
    private fun shown(resource: Int) = nodes { all -> all.any {
        visible(it) && (label(it) == base.getString(resource) ||
            base.getString(resource) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
    } }
    private fun left(resource: Int) = nodes { all ->
        all.last { label(it) == base.getString(resource) }.boundsInWindow.left
    }
    private fun click(resource: Int) {
        val text = base.getString(resource)
        fun root(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }.last()
        fun enabled(node: SemanticsNode) = visible(node) && generateSequence(node) { it.parent }.none {
            it.config.getOrNull(SemanticsProperties.Disabled) != null
        }
        fun action(node: SemanticsNode): (() -> Boolean)? {
            if (!enabled(node)) return null
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            // Merged native buttons can expose a labelled wrapper with the actual
            // action on a child; text-only nodes normally delegate to their row.
            node.children.firstNotNullOfOrNull(::action)?.let { return it }
            return null
        }
        await("Enabled clickable control missing: $text") {
            nodes { all ->
                val matches = all.filter { visible(it) && (label(it) == text ||
                    text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) }
                val topRoot = matches.lastOrNull()?.let(::root)?.id ?: return@nodes false
                val click = matches.asReversed().asSequence().filter { root(it).id == topRoot }
                    .firstNotNullOfOrNull { match ->
                        action(match) ?: generateSequence(match.parent) { it.parent }
                            .firstNotNullOfOrNull { parent ->
                                if (enabled(parent)) parent.config.getOrNull(SemanticsActions.OnClick)?.action else null
                            }
                    } ?: return@nodes false
                // Lookup and invocation share the main-thread snapshot. Do not reuse
                // a text node captured while entry/exit animation disables the control.
                assertTrue("Action is accepted: $text", click())
                true
            }
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            Thread.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun windowCount() = main { WindowInspector.getGlobalWindowViews().count { it.isShown } }
    private fun capture(dark: Boolean, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(base.cacheDir, "ui-review/onboarding-return").apply { mkdirs() }
        File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }
    private fun assertExposedEdge(reference: Bitmap, actual: Bitmap) {
        assertEquals(reference.width, actual.width)
        assertEquals(reference.height, actual.height)
        var equal = 0
        var total = 0
        // The left edge is exposed during the gesture. It must retain the actual setup page's
        // colors, rather than a dialog's gray dim layer. Avoid the system bars and corners.
        for (y in actual.height / 5 until actual.height * 4 / 5 step 17) {
            for (x in 3..12 step 3) {
                total++
                if (reference.getPixel(x, y) == actual.getPixel(x, y)) equal++
            }
        }
        assertTrue("Exposed setup edge was replaced/dimmed ($equal/$total pixels match)", equal >= total * .95)
    }
    private fun progress(scenario: ActivityScenario<MainActivity>, amount: Float = .65f) {
        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT)) }
        Thread.sleep(80)
        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 600f, amount, BackEventCompat.EDGE_LEFT)) }
        Thread.sleep(140)
    }
    private fun cancel(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
        settle()
    }
    private fun commit(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun isolatedSetup(block: (Workbench, Context) -> Unit) = runBlocking {
        warmDeviceActivity()
        val token = "onboarding-navigation-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, token).apply { mkdirs() }
        val preferenceNames = mutableSetOf<String>()
        val original = base
        val isolated = object : ContextWrapper(original) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences(
                "$token-$name".also { synchronized(preferenceNames) { preferenceNames += it } }, mode)
        }
        var workbench: Workbench? = null
        try {
            withContext(Dispatchers.IO) {
                assertTrue(isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit())
                OnboardingRepository(isolated).save(OnboardingProgress(completed = true, step = 5, environmentConfirmed = false))
            }
            val host = main { Workbench(isolated) }.also { workbench = it }
            await("Isolated setup did not load") { !host.state.value.loading }
            block(host, isolated)
        } finally {
            withContext(NonCancellable) {
                main { workbench?.stopAll() }
                delay(300)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(directory)
                    synchronized(preferenceNames) { preferenceNames.toList() }.forEach { original.deleteSharedPreferences(it) }
                }
            }
        }
    }

    @Test(timeout = 180000) fun permissionDetailsRetainSetupDuringCancelAndCommitInBothThemes() = isolatedSetup { host, context ->
        val exits = AtomicInteger()
        val completes = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                scenario.onActivity { activity -> activity.setContent {
                    val state by host.state.collectAsState()
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize()) {
                                OnboardingPage(host, state, { completes.incrementAndGet() }, { exits.incrementAndGet() })
                            }
                        }
                    }
                } }
                await("Permissions setup step missing") { shown(R.string.setup_permissions_title) }
                settle()
                val initialWindows = windowCount()
                val reference = capture(dark, "permissions-setup")
                try {
                    repeat(3) { attempt ->
                        click(R.string.setup_manage_permissions)
                        await("Permissions detail missing") { shown(R.string.app_permissions_title) }
                        settle()
                        assertEquals("Permissions must share the setup window", initialWindows, windowCount())
                        val restingLeft = left(R.string.app_permissions_title)
                        progress(scenario)
                        assertTrue("Permissions page must track partial gesture", left(R.string.app_permissions_title) > restingLeft + 12f)
                        assertEquals(5, OnboardingRepository(context).load(false).step)
                        capture(dark, "permission-progress-$attempt").let { bitmap ->
                            try { assertExposedEdge(reference, bitmap) } finally { bitmap.recycle() }
                        }
                        cancel(scenario)
                        assertTrue("Cancel must retain permissions", shown(R.string.app_permissions_title))
                        assertTrue("Cancel must settle at original position", kotlin.math.abs(left(R.string.app_permissions_title) - restingLeft) < 1.5f)
                        assertEquals(5, OnboardingRepository(context).load(false).step)
                        progress(scenario)
                        commit(scenario)
                        await("Commit did not restore setup step 5") { !shown(R.string.app_permissions_title) && shown(R.string.setup_permissions_title) }
                        assertEquals(initialWindows, windowCount())
                        assertEquals(5, OnboardingRepository(context).load(false).step)
                    }
                    capture(dark, "permissions-returned").recycle()
                    assertEquals(0, exits.get())
                    assertEquals(0, completes.get())
                } finally { reference.recycle() }
            }
        }
    }

    @Test(timeout = 180000) fun stepBackOnlyPersistsAfterCommitAndCompletedSetupRootExitsOnce() = isolatedSetup { host, context ->
        val exits = AtomicInteger()
        val completes = AtomicInteger()
        var revision by mutableIntStateOf(0)
        var fromBeginning by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                // Dispose the preceding root page before reseeding; its step-persistence effect
                // must not race the next theme's independently seeded setup state.
                scenario.onActivity { activity -> activity.setContent { Surface(Modifier.fillMaxSize()) {} } }
                instrumentation.waitForIdleSync()
                main { fromBeginning = false; revision++; host.saveOnboardingStep(5) }
                await("Initial setup step did not persist") { host.state.value.onboarding.step == 5 }
                scenario.onActivity { activity -> activity.setContent {
                    val state by host.state.collectAsState()
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize()) {
                                key(revision) { OnboardingPage(host, state, { completes.incrementAndGet() },
                                    { exits.incrementAndGet() }, startFromBeginning = fromBeginning) }
                            }
                        }
                    }
                } }
                await("Permissions step missing") { shown(R.string.setup_permissions_title) }
                settle()
                click(R.string.setup_previous)
                await("Packages step missing") { shown(R.string.setup_packages_title) && host.state.value.onboarding.step == 4 }
                settle()
                val previous = capture(dark, "packages-reference")
                try {
                    click(R.string.setup_continue)
                    await("Permissions step did not resume") { shown(R.string.setup_permissions_title) && host.state.value.onboarding.step == 5 }
                    settle()
                    val restingLeft = left(R.string.setup_permissions_title)
                    progress(scenario)
                    assertTrue("Step page must move during gesture", left(R.string.setup_permissions_title) > restingLeft + 12f)
                    assertEquals("Progress must not persist previewed step", 5, OnboardingRepository(context).load(false).step)
                    capture(dark, "step-back-progress").let { bitmap ->
                        try { assertExposedEdge(previous, bitmap) } finally { bitmap.recycle() }
                    }
                    cancel(scenario)
                    assertTrue(shown(R.string.setup_permissions_title))
                    assertEquals("Cancel must not persist previewed step", 5, OnboardingRepository(context).load(false).step)
                    progress(scenario)
                    commit(scenario)
                    await("Commit must navigate and save step 4") { shown(R.string.setup_packages_title) && host.state.value.onboarding.step == 4 }
                    assertEquals(4, OnboardingRepository(context).load(false).step)
                    capture(dark, "step-back-completed").recycle()
                } finally { previous.recycle() }
                main { fromBeginning = true; revision++ }
                await("Reopened setup root missing") { shown(R.string.setup_purpose_title) && host.state.value.onboarding.step == 0 }
                settle()
                val before = exits.get()
                progress(scenario)
                cancel(scenario)
                assertEquals("Cancelled root gesture must not exit", before, exits.get())
                assertTrue(shown(R.string.setup_purpose_title))
                assertEquals(0, OnboardingRepository(context).load(false).step)
                progress(scenario)
                capture(dark, "setup-root-progress").recycle()
                commit(scenario)
                assertEquals("Committed root gesture exits exactly once", before + 1, exits.get())
                assertEquals(0, completes.get())
            }
        }
    }

    private fun settingsScroll() = nodes { all ->
        all.last { visible(it) && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
    }

    private fun scrollToSetup() {
        repeat(20) {
            if (shown(R.string.setup_reopen)) return
            nodes { all ->
                val scroll = all.last { visible(it) && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 500f) == true)
            }
            Thread.sleep(180)
        }
        assertTrue("Initialization entry must become visible", shown(R.string.setup_reopen))
    }

    private fun assertSettingsVisibleAtExposedEdge(reference: Bitmap, actual: Bitmap) {
        assertExposedEdge(reference, actual)
        val density = base.resources.displayMetrics.density
        val colors = mutableSetOf<Int>()
        var equal = 0
        var total = 0
        // Setup moves about 36dp at .65: these columns expose the real settings cards after
        // their 20dp outer padding. A uniform WorkbenchEntrance backdrop cannot pass this.
        for (dp in 25..29 step 2) {
            val x = (density * dp).toInt().coerceIn(0, actual.width - 1)
            for (y in actual.height / 4 until actual.height * 4 / 5 step 11) {
                val expected = reference.getPixel(x, y)
                colors += expected
                total++
                if (actual.getPixel(x, y) == expected) equal++
            }
        }
        assertTrue("Settings reference must contain real card/background detail", colors.size > 1)
        assertTrue("Gesture must reveal the actual scrolled settings ($equal/$total)", equal >= total * .9)
    }

    @Test(timeout = 180000) fun reopenedSetupRevealsAndRestoresActualScrolledSettingsInBothThemes() = isolatedSetup { host, _ ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                scenario.onActivity { activity -> activity.setContent {
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration) { ZakoCoderApp(host) }
                } }
                await("Navigation control missing") { shown(R.string.app_navigation_open) }
                click(R.string.app_navigation_open)
                await("Settings shortcut missing") { shown(R.string.nav_settings) }
                click(R.string.nav_settings)
                await("Settings page missing") { shown(R.string.settings_device_hint) }
                settle()
                scrollToSetup()
                settle()
                val savedScroll = settingsScroll()
                assertTrue("This regression requires settings to be scrolled", savedScroll > 0f)
                val savedTop = nodes { all -> all.last { label(it) == base.getString(R.string.setup_reopen) }.boundsInWindow.top }
                val originalWindows = windowCount()
                val reference = capture(dark, "settings-before-reopen")
                try {
                    click(R.string.setup_reopen)
                    await("Reopened setup must start at its first step") { shown(R.string.setup_purpose_title) && host.state.value.onboarding.step == 0 }
                    settle()
                    assertEquals(originalWindows, windowCount())
                    progress(scenario)
                    capture(dark, "settings-root-return-progress").let { actual ->
                        try { assertSettingsVisibleAtExposedEdge(reference, actual) } finally { actual.recycle() }
                    }
                    cancel(scenario)
                    assertTrue("Cancellation must keep setup open", shown(R.string.setup_purpose_title))
                    progress(scenario)
                    commit(scenario)
                    await("Root gesture must return to settings", condition = { shown(R.string.setup_reopen) && !shown(R.string.setup_purpose_title) })
                    assertEquals("Settings scroll must survive setup overlay", savedScroll, settingsScroll(), 1f)
                    val restoredTop = nodes { all -> all.last { label(it) == base.getString(R.string.setup_reopen) }.boundsInWindow.top }
                    assertEquals("Initialization row must return to its original position", savedTop, restoredTop, 1f)
                    capture(dark, "settings-restored-after-setup").recycle()
                    assertEquals(originalWindows, windowCount())
                } finally { reference.recycle() }
            }
        }
    }
}
