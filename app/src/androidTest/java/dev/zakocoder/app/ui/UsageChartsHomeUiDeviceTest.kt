package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.zakocoder.app.projects.Project
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/** Production surfaces with isolated numerical/project fixtures; never touches real projects or the ledger. */
@SdkSuppress(minSdkVersion = 30)
class UsageChartsHomeUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 120000) fun day360Font130() = render(false)
    @Test(timeout = 120000) fun night360Font130() = render(true)

    private fun render(dark: Boolean) {
        var page by mutableIntStateOf(0)
        var opened by mutableIntStateOf(0)
        var terminal by mutableIntStateOf(0)
        var actions by mutableIntStateOf(0)
        val date = LocalDate.of(2026, 11, 2)
        val zone = ZoneId.of("Asia/Shanghai")
        val start = date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val state = AiUsageState(loading = false, requests = (0 until 12).map { index ->
            AiUsageRequest("request-$index", "p", "c", if (index % 3 == 0) "provider/model-long-name-2026-test" else "model-beta",
                start + (index % 8) * 86400000L, true, false, 1000L * index, 500L, if (index % 2 == 0) 500L else null, 10000, 1000000, false)
        }, tools = (0..4).map { AiUsageTool("tool-$it", "p", "c", "model-beta", start + it * 86400000L, "Succeeded", true) })
        val buckets = AiUsageCharts.buckets(state, date, false, zone)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides config,
                    LocalDensity provides Density(context.resources.displayMetrics.widthPixels / 360f, 1.3f), LocalWorkbenchDark provides dark,
                    LocalMinimumInteractiveComponentSize provides 48.dp) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            key(page) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    when (page) {
                                        0 -> UsageTrendCard(buckets, false, zone)
                                        1 -> UsageModelDistribution(AiUsageCharts.models(state))
                                        2 -> {
                                            Text(context.getString(R.string.projects_title), style = MaterialTheme.typography.titleLarge)
                                            ProjectEnvironmentSummary(true, context.getString(R.string.library_environment_summary, 5, 12)) {}
                                            HomeProjectCard(Project("p", "移动端管理控制台与客户端应用 / alpha", setOf(ProjectKind.Android, ProjectKind.Node)),
                                                "/storage/emulated/0/Projects/企业开发环境/移动端管理控制台", true, 2, true,
                                                { opened++ }, { actions++ }, {}, { terminal++ })
                                        }
                                        else -> {
                                            ProjectEnvironmentSummary(false, context.getString(R.string.library_environment_short)) {}
                                            HomeProjectCard(Project("p2", "CLI", emptySet()), null, false, 0, true,
                                                { opened++ }, { actions++ }, {}, { terminal++ })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            await("Trend title") { shown(context.getString(R.string.usage_chart_title)) }
            tapChart(0)
            await("Tapping first bar selects its request count") {
                shown(context.getString(R.string.usage_chart_requests_value, 2))
            }
            tapChart(14)
            await("Tapping an empty mid-month slot directly selects November 15") {
                shown("11-15") && shown(context.getString(R.string.usage_chart_requests_value, 0))
            }
            capture(dark, "trend-requests")
            click(context.getString(R.string.usage_chart_tokens))
            await("Token explanation") { shown(context.getString(R.string.usage_chart_reported)) }
            click(context.getString(R.string.usage_chart_previous))
            capture(dark, "trend-tokens")
            click(context.getString(R.string.usage_chart_tools))
            capture(dark, "trend-tools")
            instrumentation.runOnMainSync { page = 1 }
            await("Distribution") { shown(context.getString(R.string.usage_chart_model_title)) }
            capture(dark, "model-distribution")
            instrumentation.runOnMainSync { page = 2 }
            await("Project state") { shown(context.getString(R.string.home_current_project)) }
            capture(dark, "home-bound")
            click(context.getString(R.string.browse_files))
            assertEquals(1, opened)
            click(context.getString(R.string.open_terminal))
            assertEquals(1, terminal)
            click(context.getString(R.string.home_project_actions, "移动端管理控制台与客户端应用 / alpha"))
            assertEquals(1, actions)
            instrumentation.runOnMainSync { page = 3 }
            await("Managed project") { shown("CLI") }
            capture(dark, "home-managed")
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
    private fun tapChart(index: Int) {
        val chartDescription = context.getString(R.string.usage_chart_accessible,
            context.getString(R.string.usage_chart_requests), 30)
        val bounds = nodes { all ->
            all.single { chartDescription in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInWindow
        }
        val x = bounds.left + bounds.width * (index + .5f) / 30f
        val y = bounds.center.y
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { label }; Thread.sleep(50) }
    }
    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val directory = File(context.cacheDir, "ui-review/product-polish").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
