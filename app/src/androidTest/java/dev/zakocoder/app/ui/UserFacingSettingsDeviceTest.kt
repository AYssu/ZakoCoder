package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.OnboardingProgress
import dev.zakocoder.app.settings.OnboardingRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Production settings navigation, real package version and bundled license text; isolated app data. */
@SdkSuppress(minSdkVersion = 30)
class UserFacingSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun day360NormalFont() = render(false)
    @Test(timeout = 180000) fun night360NormalFont() = render(true)

    private fun render(dark: Boolean) = runBlocking {
        val token = "settings-copy-ui-${UUID.randomUUID()}"
        val own = File(base.cacheDir, token).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val original = base
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences("$token-$name".also { names += it }, mode)
        }
        withContext(Dispatchers.IO) {
            isolated.getSharedPreferences("app_updates", 0).edit().putBoolean("automatic", false).commit()
            // Exercise settings after the separate first-run guide; never initialize a runtime.
            OnboardingRepository(isolated).save(OnboardingProgress(completed = true, environmentConfirmed = false))
        }
        val workbench = withContext(Dispatchers.Main) { Workbench(isolated) }
        try {
            await("Isolated workbench ready") { !workbench.state.value.loading }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    @Suppress("DEPRECATION")
                    activity.window.statusBarColor = if (dark) android.graphics.Color.rgb(11, 15, 20) else android.graphics.Color.rgb(245, 247, 250)
                    @Suppress("DEPRECATION")
                    activity.window.navigationBarColor = if (dark) android.graphics.Color.rgb(21, 27, 35) else android.graphics.Color.WHITE
                    activity.setContent {
                    val config = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    CompositionLocalProvider(LocalConfiguration provides config,
                        LocalDensity provides Density(base.resources.displayMetrics.widthPixels / 360f, 1f)) {
                        ZakoCoderApp(workbench)
                    }
                    }
                }
                click(base.getString(R.string.app_navigation_open))
                await("Settings destination visible in navigation drawer") { shown(base.getString(R.string.nav_settings)) }
                click(base.getString(R.string.nav_settings))
                await("Production settings ready") { shown(base.getString(R.string.ai_settings)) }
                capture(dark, "settings")
                nodes { all -> assertFalse("Assistant group no longer duplicates tool permissions",
                    all.any { label(it) == base.getString(R.string.ai_permissions) }) }
                val originalSettings = workbench.state.value.settings
                val dns = base.getString(R.string.dns_short)
                val dnsValue = originalSettings.dns.split(',').joinToString(", ") { it.trim() }
                centerRow(dns)
                await("DNS title and summary laid out") { measuredText(dns) != null && measuredText(dnsValue) != null }
                val dnsTitle = requireNotNull(measuredText(dns))
                val dnsSummary = requireNotNull(measuredText(dnsValue))
                assertEquals("DNS title uses compact native heading", 14.sp, dnsTitle.first.layoutInput.style.fontSize)
                assertEquals("DNS value is a 12sp native summary", 12.sp, dnsSummary.first.layoutInput.style.fontSize)
                assertEquals("DNS summary starts with its title", dnsTitle.second, dnsSummary.second, 1f)
                capture(dark, "network-summary")
                val cache = base.getString(R.string.cache_compact_action)
                centerRow(cache)
                nodes { all ->
                    val icon = all.last { matches(it, cache) && visible(it) }
                    val button = requireNotNull(generateSequence(icon) { it.parent }.firstOrNull {
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                    }) { "Accessible cache icon button" }
                    val density = base.resources.displayMetrics.widthPixels / 360f
                    assertTrue("Cache icon retains 48dp width", button.boundsInWindow.width / density >= 47.5f)
                    assertTrue("Cache icon retains 48dp height", button.boundsInWindow.height / density >= 47.5f)
                    assertTrue("Cache icon exposes its limited cleanup scope", generateSequence(icon) { it.parent }.any {
                        it.config.getOrNull(SemanticsProperties.StateDescription) == base.getString(R.string.cache_scope)
                    })
                    assertFalse("Cache action is an icon, not a large text button", all.any {
                        label(it) == cache && visible(it)
                    })
                }
                capture(dark, "storage-cache-icon")
                assertEquals("Inspecting network/storage does not change settings", originalSettings, workbench.state.value.settings)
                @Suppress("DEPRECATION")
                val installed = requireNotNull(base.packageManager.getPackageInfo(base.packageName, 0).versionName)
                val footer = base.getString(R.string.settings_version_footer, installed)
                val about = base.getString(R.string.settings_about_entry)
                scrollUntil(about, 600f)
                scrollUntil(footer, 400f)
                assertTrue("About entry shows actual package version", shown(footer))
                click(about)
                await("About displays actual installed package version") { shown(installed) && shown(base.getString(R.string.about_product_description)) }
                nodes { all -> assertFalse(all.any { label(it).contains("维护者审查") }) }
                capture(dark, "about-version")
                scrollUntil(base.getString(R.string.about_license_summary), 400f)
                click(base.getString(R.string.about_license_summary))
                await("Updated component versions are available") { nodes { all -> all.any { label(it).contains("CommonMark Java 0.32.0") && label(it).contains("OkHttp 5.5.1 / Okio 3.18.1") } } }
                capture(dark, "about-components")
                click(base.getString(R.string.about_license_summary))
                scrollUntil(base.getString(R.string.about_license_originals), 400f)
                click(base.getString(R.string.about_license_originals))
                await("License asset picker loaded") { shown("Apache-2.0") }
                capture(dark, "license-list")
                click("Apache-2.0")
                await("Original Apache license is readable") { nodes { all -> all.any { label(it).contains("Apache License") && label(it).contains("Version 2.0, January 2004") } } }
                capture(dark, "license-fulltext")
                click(base.getString(R.string.close))
                await("License sheet finishes closing") {
                    nodes { all -> all.none { label(it).contains("Version 2.0, January 2004") } }
                }
                scrollUntil(base.getString(R.string.about_product_description), -400f)
                await("About remains open") { shown(base.getString(R.string.about_product_description)) }
                click(base.getString(R.string.close))
                await("About finishes closing back to settings") {
                    shown(about) && !shown(base.getString(R.string.about_product_description))
                }
                val proxy = base.getString(R.string.proxy_short)
                scrollUntil(proxy, -500f)
                val originalProxy = workbench.state.value.settings.proxy
                click(proxy)
                await("Independent proxy page ready") { shown(base.getString(R.string.proxy_page_title)) }
                await("External proxy settings visible") {
                    shown(base.getString(R.string.proxy_section_manual)) && shown(base.getString(R.string.proxy_manual_label))
                }
                capture(dark, "proxy-manual")
                scrollUntil(base.getString(R.string.proxy_mode_manual_hint), 400f)
                assertTrue(shown(base.getString(R.string.proxy_manual_help)))
                assertEquals("Opening manual mode must not save a proxy", originalProxy, workbench.state.value.settings.proxy)
                capture(dark, "proxy-manual-help")

            }
        } finally {
            withContext(NonCancellable) {
                instrumentation.runOnMainSync { workbench.stopAll() }
                delay(250)
                withContext(Dispatchers.IO) { PrivateFiles.deleteTree(own); names.forEach { original.deleteSharedPreferences(it) } }
            }
        }
    }
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }
    /** Return the actual rendered layout and its first-line start in window coordinates. */
    private fun measuredText(text: String): Pair<TextLayoutResult, Float>? = nodes { all ->
        all.asReversed().firstNotNullOfOrNull { node ->
            if (label(node) != text || !visible(node) || node.boundsInWindow.height < node.size.height * .95f)
                return@firstNotNullOfOrNull null
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.firstOrNull()?.let { it to (node.layoutInfo.coordinates.localToWindow(Offset.Zero).x + it.getLineLeft(0)) }
        }
    }
    private fun centerRow(text: String) {
        scrollUntil(text, 600f)
        nodes { all ->
            val target = all.last { matches(it, text) && visible(it) }
            val scroll = requireNotNull(generateSequence(target.parent) { it.parent }.firstOrNull {
                it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }) { "Scrollable settings row: $text" }
            val y = target.layoutInfo.coordinates.localToWindow(Offset.Zero).y + target.size.height / 2f
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, y - scroll.boundsInWindow.center.y) == true)
        }
        Thread.sleep(250)
    }
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun matches(node: SemanticsNode, text: String) = label(node) == text ||
        text in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        val display = base.resources.displayMetrics
        return bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < display.widthPixels && bounds.top < display.heightPixels
    }
    private fun shown(text: String) = nodes { all -> all.any { matches(it, text) && visible(it) } }
    private fun click(text: String) {
        await("Visible action: $text") { shown(text) }
        nodes { all ->
            var node: SemanticsNode? = all.last { matches(it, text) && visible(it) }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun scrollUntil(text: String, amount: Float) {
        repeat(16) {
            if (shown(text)) return
            scroll(amount)
        }
        assertTrue("Visible text: $text", shown(text))
    }
    private fun scroll(amount: Float) {
        nodes { all -> all.lastOrNull { visible(it) && it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            ?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, amount) }
        Thread.sleep(150)
    }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { description }; Thread.sleep(50) }
    }
    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val directory = File(base.cacheDir, "ui-review/user-facing-copy").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
