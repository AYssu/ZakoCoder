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
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual app navigation + actual complete pages. All repositories/preferences live in a fresh fixture. */
@SdkSuppress(minSdkVersion = 30)
class ProductDashboardFullPageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun day360Font130() = render(false)
    @Test(timeout = 180000) fun night360Font130() = render(true)

    private fun render(dark: Boolean) = runBlocking {
        val token = "full-product-ui-${UUID.randomUUID()}"
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
        val repo = ProjectRepository(isolated, File(isolated.filesDir, "workspaces").apply { mkdirs() })
        val projects = withContext(Dispatchers.IO) {
            listOf(repo.create("移动端管理控制台"), repo.create("CLI 开发工具")).also { repo.save(it); repo.select(it.first().id) }
        }
        val now = System.currentTimeMillis()
        AiUsageStore(isolated).record((0 until 12).map { index ->
            AiUsageRequest("request-$index", projects[index % 2].id, "fixture", if (index % 2 == 0) "model-alpha" else "model-beta",
                now - index * 1000L, true, false, 1000L * (index + 1), 200, 500, 10000, 1000000, false)
        }, emptyList())
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
                await("Real homepage with search and project") { shown("移动端管理控制台") && shown(base.getString(R.string.search_projects)) }
                capture(dark, "home-full")
                nodes { all ->
                    val field = all.first { it.config.getOrNull(SemanticsActions.SetText) != null }
                    assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("CLI")) == true)
                }
                await("Search filters complete homepage") { shown("CLI 开发工具") && !shown("移动端管理控制台") }
                capture(dark, "home-search")
                click(base.getString(R.string.clear_search))
                click(base.getString(R.string.nav_settings))
                val usage = base.getString(R.string.ai_usage_dashboard)
                repeat(8) { if (!shown(usage)) scroll(360f) }
                await("Usage entry in real settings") { shown(usage) }
                click(usage)
                await("Full dashboard model and project filters") { shown(base.getString(R.string.ai_usage_all_models)) && shown(base.getString(R.string.ai_usage_all_projects)) }
                capture(dark, "usage-full")
                click(base.getString(R.string.ai_usage_all_models))
                await("Actual model filter") { shown("model-beta") }
                capture(dark, "usage-model-filter")
                click("model-beta")
                await("Filtered model value") { shown("model-beta") }
                capture(dark, "usage-filtered")
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
    private fun shown(text: String) = nodes { all -> all.any { label(it) == text && it.boundsInWindow.height > 0 } }
    private fun click(text: String) = nodes { all ->
        var node: SemanticsNode? = all.last { label(it) == text || text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun scroll(amount: Float) {
        nodes { all -> all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
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
        val directory = File(base.cacheDir, "ui-review/product-polish").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
