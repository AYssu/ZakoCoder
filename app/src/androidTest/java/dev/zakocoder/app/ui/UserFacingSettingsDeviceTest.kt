package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.runtime.PrivateFiles
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
    @Test(timeout = 180000) fun day360Font130() = render(false)
    @Test(timeout = 180000) fun night360Font130() = render(true)

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
        isolated.getSharedPreferences("app_updates", 0).edit().putBoolean("automatic", false).commit()
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
                        LocalDensity provides Density(base.resources.displayMetrics.widthPixels / 360f, 1.3f)) {
                        ZakoCoderApp(workbench)
                    }
                    }
                }
                click(base.getString(R.string.app_navigation_open))
                await("Settings destination visible in navigation drawer") { shown(base.getString(R.string.nav_settings)) }
                click(base.getString(R.string.nav_settings))
                await("Production settings ready") { shown(base.getString(R.string.ai_settings)) }
                capture(dark, "settings")
                @Suppress("DEPRECATION")
                val installed = requireNotNull(base.packageManager.getPackageInfo(base.packageName, 0).versionName)
                val footer = base.getString(R.string.settings_version_footer, installed)
                scrollUntil(footer, 600f)
                click(footer)
                await("About displays actual installed package version") { shown(installed) && shown(base.getString(R.string.about_product_description)) }
                nodes { all -> assertFalse(all.any { label(it).contains("维护者审查") }) }
                capture(dark, "about-version")
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
                await("About remains open") { shown(base.getString(R.string.about_product_description)) }
                click(base.getString(R.string.close))
                val proxy = base.getString(R.string.proxy_short)
                scrollUntil(proxy, -500f)
                val originalProxy = workbench.state.value.settings.proxy
                click(proxy)
                await("Independent proxy page ready") { shown(base.getString(R.string.proxy_page_title)) }
                await("All proxy connection modes visible") {
                    listOf(R.string.proxy_mode_direct, R.string.proxy_mode_manual, R.string.proxy_mode_airport)
                        .all { shown(base.getString(it)) }
                }
                capture(dark, "proxy-modes")
                click(base.getString(R.string.proxy_mode_manual))
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
