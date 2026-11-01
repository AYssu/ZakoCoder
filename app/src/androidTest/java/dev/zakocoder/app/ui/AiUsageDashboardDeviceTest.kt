package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/** Isolated numerical fixtures only. Does not read or update user profiles, projects or the ledger. */
@SdkSuppress(minSdkVersion = 30)
class AiUsageDashboardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val today = LocalDate.of(2026, 11, 1)
    private val started = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val fixture = AiUsageState(loading = false, startedAt = started - 100L * 86400000,
        requests = (0 until 41).map { n -> AiUsageRequest("request-$n", if (n % 4 < 2) "p1" else "p2", "session",
            if (n % 2 == 0) "fixture-model-alpha" else "fixture-model-beta", if (n == 40) started - 50L * 86400000 else started + n * 1000,
            complete = n != 38, failed = n == 37, inputTokens = 1000, outputTokens = if (n == 38) null else 100,
            cachedTokens = if (n % 2 == 0) 500 else null, estimatedContextTokens = 10000L + n,
            contextWindowTokens = 1050000, compaction = false) },
        tools = (0..2).map { AiUsageTool("tool-$it", "p1", "session", "fixture-model-alpha", started + it * 1000,
            if (it == 2) "Waiting" else "Succeeded", executed = it != 2) })

    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 120000) fun day360Font130() = render(false)
    @Test(timeout = 120000) fun night360Font130() = render(true)

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
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!predicate()) { check(SystemClock.uptimeMillis() < until) { label }; Thread.sleep(50) }
    }
    private fun shown(label: String) = nodes { all -> all.any { text(it) == label && it.boundsInWindow.height > 0 } }
    private fun click(label: String) = nodes { all ->
        var node: SemanticsNode? = all.last { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun scroll(amount: Float) {
        nodes { all ->
            val target = all.first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            assertTrue(target.config[SemanticsActions.ScrollBy].action?.invoke(0f, amount) == true)
        }
        Thread.sleep(200)
    }

    private fun render(dark: Boolean) {
        var page by mutableIntStateOf(0)
        var failed by mutableStateOf(true)
        var retries by mutableIntStateOf(0)
        val projects = mapOf("p1" to "移动端项目", "p2" to "前端工程（隔离测试数据）")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                val density = context.resources.displayMetrics.widthPixels / 360f
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, 1.3f), LocalWorkbenchDark provides dark,
                    LocalMinimumInteractiveComponentSize provides 48.dp) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.fillMaxSize()) {
                                Text(context.getString(R.string.ai_usage_dashboard), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                                key(page) {
                                    when (page) {
                                        1 -> AiRequestsPanel(AiState(projectId = "p1", conversationId = "session", requests = listOf(
                                            AiRequestLog(id = "detail-1", model = "fixture-model-alpha", complete = true),
                                            AiRequestLog(id = "detail-2", model = "fixture-model-beta", complete = true))),
                                            Modifier.weight(1f).padding(horizontal = 16.dp), fixture)
                                        2 -> AiUsageDashboard(fixture.copy(requests = emptyList(), tools = emptyList()), projects, {}, today = today)
                                        3 -> AiUsageDashboard(fixture.copy(failed = failed), projects,
                                            { retries++; failed = false }, today = today)
                                        4 -> AiUsageDashboard(AiUsageState(), projects, {}, today = today)
                                        5 -> AiUsageDashboard(fixture.copy(truncated = true, retainedFrom = started, incomplete = true), projects, {}, today = today)
                                        else -> AiUsageDashboard(fixture, projects, {}, today = today)
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            await("Month scope excludes the older request") { shown("40") }
            capture(dark, "dashboard")
            scroll(480f)
            await("Cache metric is visible") { shown(context.getString(R.string.ai_usage_cache_hit)) }
            capture(dark, "metrics")
            scroll(-1500f)
            click(context.getString(R.string.ai_usage_all_models))
            await("Model filter sheet") { shown(context.getString(R.string.ai_usage_search)) }
            capture(dark, "model-filter")
            click("fixture-model-beta")
            await("Model filter selects 20 requests") { shown("20") }
            click(context.getString(R.string.ai_usage_all_projects))
            await("Project filter sheet") { shown("移动端项目") }
            capture(dark, "project-filter")
            click("移动端项目")
            await("Combined model and project filters select 10 requests") { shown("10") }
            capture(dark, "filtered")
            click(context.getString(R.string.ai_usage_day))
            await("Day period uses the chosen date") { shown("2026-11-01") }
            click(context.getString(R.string.ai_usage_previous_period))
            await("Empty previous day") { shown(context.getString(R.string.ai_usage_empty)) }
            capture(dark, "empty")
            instrumentation.runOnMainSync { page = 1 }
            await("Session totals use 21 project-owned ledger records rather than two retained details") { shown("21") }
            assertFalse("Same conversation ID in another project must not enter session totals", shown("41"))
            capture(dark, "session-summary")
            scroll(480f)
            capture(dark, "session-metrics")
            instrumentation.runOnMainSync { page = 3 }
            await("Read error is explicit") { shown(context.getString(R.string.ai_usage_load_failed)) }
            capture(dark, "error")
            click(context.getString(R.string.ai_usage_retry))
            await("Retry is wired and statistics return") { retries == 1 && shown("40") }
            instrumentation.runOnMainSync { page = 4 }
            await("Loading does not display zero totals") { shown(context.getString(R.string.ai_usage_loading)) }
            assertFalse(shown(context.getString(R.string.ai_usage_requests)))
            capture(dark, "loading")
            instrumentation.runOnMainSync { page = 5 }
            await("Retention truncation is explicit before totals") { shown(context.getString(R.string.ai_usage_partial_title)) }
            capture(dark, "retained")
        }
    }

    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        assertTrue(frames.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val root = File(context.cacheDir, "ui-review/usage-dashboard-after").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
