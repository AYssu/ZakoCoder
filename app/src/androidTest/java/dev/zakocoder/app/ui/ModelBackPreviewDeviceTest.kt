package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiModelDraft
import dev.zakocoder.app.ai.AiPermissionStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual application shell, model list and editor; isolated profiles, no model requests. */
@SdkSuppress(minSdkVersion = 30)
class ModelBackPreviewDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private val profileName = "RETURN_PREVIEW_ALPHA"

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
    private fun matches(node: SemanticsNode, text: String) = label(node) == text ||
        text in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        val screen = base.resources.displayMetrics
        return bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < screen.widthPixels && bounds.top < screen.heightPixels
    }
    private fun shown(text: String) = nodes { all -> all.any { visible(it) && matches(it, text) } }
    private fun shown(resource: Int) = shown(base.getString(resource))
    private fun bounds(text: String) = nodes { all -> all.last { visible(it) && label(it) == text }.boundsInWindow }
    private fun click(resource: Int) = click(base.getString(resource))
    private fun click(text: String) {
        fun action(node: SemanticsNode): (() -> Boolean)? {
            if (!visible(node) || node.config.getOrNull(SemanticsProperties.Disabled) != null) return null
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            return node.children.firstNotNullOfOrNull(::action)
        }
        await("Enabled action missing: $text") {
            nodes { all ->
                val click = all.asReversed().filter { visible(it) && matches(it, text) }
                    .firstNotNullOfOrNull { node ->
                        action(node) ?: generateSequence(node.parent) { it.parent }.firstNotNullOfOrNull(::action)
                    } ?: return@nodes false
                assertTrue("Click accepted: $text", click())
                true
            }
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                bitmap?.let { image ->
                    File(base.cacheDir, "ui-review/model-back-return/entry-failure.png").apply { parentFile.mkdirs() }
                        .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                error(message)
            }
            Thread.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun progress(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT)) }
        Thread.sleep(80)
        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 600f, .75f, BackEventCompat.EDGE_LEFT)) }
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
    private fun capture(dark: Boolean, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(base.cacheDir, "ui-review/model-back-return/${if (dark) "night" else "day"}-$name.png")
            .apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        return bitmap
    }

    private fun assertRealParent(reference: Bitmap, actual: Bitmap, marker: Rect) {
        assertEquals(reference.width, actual.width)
        assertEquals(reference.height, actual.height)
        val colors = mutableMapOf<Int, Int>()
        var equal = 0
        var total = 0
        fun colorDistance(first: Int, second: Int) = maxOf(
            kotlin.math.abs(Color.red(first) - Color.red(second)),
            kotlin.math.abs(Color.green(first) - Color.green(second)),
            kotlin.math.abs(Color.blue(first) - Color.blue(second)))
        // Sample parent text/card content, not just an identically colored surface edge.
        // At .75 the outgoing page reveals more than half the width; use its left third.
        val x0 = marker.left.toInt().coerceAtLeast(0)
        val x1 = minOf(marker.right.toInt(), (actual.width * .36f).toInt())
        assertTrue("Parent marker must be within the revealed content", x1 > x0)
        for (y in marker.top.toInt().coerceAtLeast(0) until marker.bottom.toInt().coerceAtMost(actual.height)) {
            for (x in x0 until x1) {
                val expected = reference.getPixel(x, y)
                colors[expected] = (colors[expected] ?: 0) + 1
                total++
                // Retaining a page adds an offscreen graphics layer. On the emulator the
                // same glyph pixels differ by 1–5 RGB levels after that compositing pass,
                // with no position change; exact ARGB equality rejects legitimate AA.
                if (colorDistance(actual.getPixel(x, y), expected) <= 5) equal++
            }
        }
        assertTrue("Reference must contain actual parent text, not a solid backdrop", colors.size > 5)
        assertTrue("Gesture must expose unaltered parent content ($equal/$total)", total > 0 && equal >= total * .97)
        val background = colors.maxBy { it.value }.key
        var foreground = 0
        var retained = 0
        for (y in marker.top.toInt().coerceAtLeast(0) until marker.bottom.toInt().coerceAtMost(actual.height)) {
            for (x in x0 until x1) {
                val expected = reference.getPixel(x, y)
                if (colorDistance(expected, background) <= 32) continue
                foreground++
                if (colorDistance(actual.getPixel(x, y), expected) <= 5) retained++
            }
        }
        assertTrue("Marker must contain readable foreground glyphs", foreground > 20)
        assertTrue("Actual parent glyphs must retain their position and color ($retained/$foreground)",
            retained >= foreground * .97)
    }

    private fun isolated(block: (Workbench, AiDeviceTest.Fixture) -> Unit) = runBlocking {
        warmDeviceActivity()
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(base) }
        val original = base
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(original) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences(
                "${fixture.token}-$name".also { synchronized(preferences) { preferences += it } }, mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        try {
            withContext(Dispatchers.IO) {
                assertTrue(isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit())
                OnboardingRepository(isolated).save(OnboardingProgress(completed = true, step = 5, environmentConfirmed = false))
                fixture.config.saveProfile(AiModelDraft("legacy", profileName, fixture.server.url("/v1").toString(), "preview-model-alpha", ""))
                fixture.config.saveProfile(AiModelDraft(null, "RETURN_PREVIEW_BETA", fixture.server.url("/v1").toString(), "preview-model-beta", ""))
            }
            val host = main { Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }.also { owned = it }
            await("Isolated workbench/model catalog ready") { !host.state.value.loading && !host.ai.models.state.value.loading }
            block(host, fixture)
            assertEquals("Navigation must never request model services", 0, fixture.server.requestCount)
        } finally {
            withContext(NonCancellable) {
                main { owned?.stopAll() }
                delay(300)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(File(fixture.root, "files"))
                    synchronized(preferences) { preferences.toList() }.forEach { original.deleteSharedPreferences(it) }
                    fixture.close()
                }
            }
        }
    }

    private fun openSettings(host: Workbench, dark: Boolean, scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity -> activity.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) { ZakoCoderApp(host) }
        } }
        await("Assistant navigation ready") { shown(R.string.app_navigation_open) }
        settle()
        click(R.string.app_navigation_open)
        await("Settings drawer shortcut visible") { nodes { all ->
            all.any { node -> matches(node, base.getString(R.string.nav_settings)) && visible(node) &&
                generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Selected) != null } }
        } }
        settle()
        nodes { all ->
            val label = all.last { node -> matches(node, base.getString(R.string.nav_settings)) && visible(node) &&
                generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Selected) != null } }
            val button = generateSequence(label) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        await("Actual settings parent ready") { shown(R.string.settings_device_hint) && shown(R.string.ai_settings) }
        settle()
    }

    private fun openModels() {
        click(R.string.ai_settings)
        await("Model list ready") { shown(profileName) && shown(R.string.ai_models_add) }
        settle()
    }

    @Test(timeout = 180000)
    fun existingModelEditorRevealsRealModelCardsAndCancelRestoresEditorInBothThemes() = isolated { host, _ ->
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettings(host, dark, scenario)
            openModels()
            val marker = bounds(profileName)
            val reference = capture(dark, "list-before-edit")
            try {
                click(base.getString(R.string.ai_models_edit_named, profileName))
                await("Editor ready") { shown(R.string.ai_models_edit_title) }
                settle()
                val resting = bounds(base.getString(R.string.ai_models_edit_title)).left
                progress(scenario)
                assertTrue("Editor must move during gesture", bounds(base.getString(R.string.ai_models_edit_title)).left > resting + 12f)
                capture(dark, "edit-return-progress").let { actual ->
                    try { assertRealParent(reference, actual, marker) } finally { actual.recycle() }
                }
                cancel(scenario)
                assertTrue(shown(R.string.ai_models_edit_title))
                assertEquals(resting, bounds(base.getString(R.string.ai_models_edit_title)).left, 1.5f)
                progress(scenario)
                commit(scenario)
                await("Editor commit returns to model list") { shown(profileName) && !shown(R.string.ai_models_edit_title) }
                capture(dark, "edit-return-completed").recycle()
                assertEquals(2, host.ai.models.state.value.catalog.profiles.size)
            } finally { reference.recycle() }
        }
    }

    @Test(timeout = 180000)
    fun newModelDraftSurvivesCancelledGestureAndDiscardConfirmationInBothThemes() = isolated { host, _ ->
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettings(host, dark, scenario)
            openModels()
            val marker = bounds(profileName)
            val reference = capture(dark, "list-before-add")
            try {
                click(R.string.ai_models_add)
                await("New model name field ready") { nodes { all -> all.any { visible(it) && it.config.getOrNull(SemanticsActions.SetText) != null } } }
                val draft = "UNSAVED_RETURN_DRAFT"
                nodes { all ->
                    val name = all.first { visible(it) && it.config.getOrNull(SemanticsActions.SetText) != null }
                    assertTrue(name.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true)
                }
                settle()
                progress(scenario)
                capture(dark, "add-return-progress").let { actual ->
                    try { assertRealParent(reference, actual, marker) } finally { actual.recycle() }
                }
                cancel(scenario)
                fun assertDraft() = assertTrue("Cancelled Back retains unsaved model draft", nodes { all -> all.any {
                    visible(it) && it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
                } })
                assertDraft()
                assertFalse(shown(R.string.ai_models_discard_title))
                progress(scenario)
                commit(scenario)
                await("Dirty editor requires discard confirmation") { shown(R.string.ai_models_discard_title) }
                click(R.string.ai_models_keep_editing)
                await("Keep editing closes the confirmation window") { !shown(R.string.ai_models_discard_title) }
                settle()
                assertDraft()
                commit(scenario)
                await("Discard confirmation reopened") { shown(R.string.ai_models_discard_title) }
                click(R.string.ai_models_discard)
                await("Discard returns to original model list") { shown(profileName) && !shown(R.string.ai_models_discard_title) }
                assertEquals("Discard never adds a profile", 2, host.ai.models.state.value.catalog.profiles.size)
                capture(dark, "add-discard-completed").recycle()
            } finally { reference.recycle() }
        }
    }

    @Test(timeout = 180000)
    fun modelListRevealsActualSettingsThroughApplicationShellInBothThemes() = isolated { host, _ ->
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettings(host, dark, scenario)
            val marker = bounds(base.getString(R.string.ai_settings))
            val reference = capture(dark, "settings-before-models")
            try {
                openModels()
                progress(scenario)
                capture(dark, "list-return-progress").let { actual ->
                    try { assertRealParent(reference, actual, marker) } finally { actual.recycle() }
                }
                cancel(scenario)
                assertTrue("Cancelled return keeps model list", shown(profileName))
                progress(scenario)
                commit(scenario)
                await("List returns to settings, not assistant/drawer") { shown(R.string.settings_device_hint) && !shown(R.string.ai_models_add) }
                assertEquals(marker.top, bounds(base.getString(R.string.ai_settings)).top, 1f)
                capture(dark, "settings-restored").recycle()
            } finally { reference.recycle() }
        }
    }
}
