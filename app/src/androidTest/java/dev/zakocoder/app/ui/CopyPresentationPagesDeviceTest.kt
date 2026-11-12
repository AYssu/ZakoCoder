package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
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
import dev.zakocoder.app.mcp.McpController
import dev.zakocoder.app.updates.*
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Production components with isolated presentation fixtures, never copied page layouts:
 * ModelOptions, ContextBudgetOptions, McpImportDialog, AiUsageDashboard, UpdateContent.
 * The model draft is blank, MCP storage uses a temporary directory, usage is deliberately empty,
 * and update state is a static fixture. No real configuration or records are used; no network,
 * processes, installation or user projects are involved. Screenshots do not prove those operations.
 */
@SdkSuppress(minSdkVersion = 30)
class CopyPresentationPagesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun day360Font130() = verify(false)
    @Test(timeout = 180000) fun night360Font130() = verify(true)

    private fun verify(dark: Boolean) {
        var selected by mutableIntStateOf(0)
        val mounted = java.util.concurrent.atomic.AtomicInteger(-1)
        val temporary = File(context.cacheDir, "copy-page-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val alias = "copy-page-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = temporary }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val mcp = McpController(isolated, scope, keyAlias = alias)
        try {
            await("Isolated MCP configuration loaded") { !mcp.state.value.loading }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration,
                        LocalDensity provides Density(context.resources.displayMetrics.widthPixels / 360f, 1.3f),
                        LocalWorkbenchDark provides dark, LocalMinimumInteractiveComponentSize provides 48.dp) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                key(selected) {
                                    val draft = remember { ModelOptionsDraft(null) }
                                    when (selected) {
                                        0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                            ModelOptions(draft, null, true) {}
                                        }
                                        1 -> McpImportDialog(mcp, true) {}
                                        2 -> AiUsageDashboard(AiUsageState(loading = false, startedAt = 1793548800000L),
                                            emptyMap(), {}, today = LocalDate.of(2026, 11, 2))
                                        3 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            ContextBudgetOptions(draft, true) {}
                                        }
                                        4 -> UpdateContent(UpdateState(initialized = true, currentVersion = "0.1.0-beta.2", phase = UpdatePhase.Current),
                                            { _, _ -> }, {}, {}, {}, {})
                                        else -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                            AiComposerModelButton(AiProfile("fixture", "模型展示", "https://example.invalid", "model-example", false,
                                                AiParameters(reasoningEffort = AiReasoningEffort.High)), {})
                                        }
                                    }
                                    SideEffect { mounted.set(selected) }
                                }
                            }
                        }
                    }
                } }
                fun page(index: Int) {
                    instrumentation.runOnMainSync { selected = index }
                    await("Fixture page mounted: $index") { mounted.get() == index }
                    instrumentation.waitForIdleSync()
                }
                page(0)
                click(context.getString(R.string.ai_options_title))
                revealHelp(R.string.copy_ai_thinking_help_title, R.string.copy_ai_thinking_help)
                capture(dark, "model-options")
                page(1)
                revealHelp(R.string.copy_ai_mcp_import_help_title, R.string.copy_ai_mcp_import_help)
                capture(dark, "mcp-import")
                page(2)
                await("Empty usage state visible") { shown(context.getString(R.string.ai_usage_empty)) }
                capture(dark, "usage-empty")
                scrollTo(context.getString(R.string.copy_ai_usage_help_title))
                click(context.getString(R.string.copy_ai_usage_help_title))
                instrumentation.waitForIdleSync()
                scrollTo(context.getString(R.string.copy_ai_usage_help))
                await("Usage explanation expanded") { shown(context.getString(R.string.copy_ai_usage_help)) }
                capture(dark, "usage-details")
                page(3)
                capture(dark, "context-options")
                revealHelp(R.string.copy_ai_context_help_title, R.string.copy_ai_context_help)
                capture(dark, "context-details")
                page(4)
                revealHelp(R.string.update_security_details_title, R.string.update_security_details_body)
                capture(dark, "update-details")
                page(5)
                await("Composer uses translated reasoning label") { shown(context.getString(R.string.ai_effort_high)) }
                capture(dark, "composer-model")
            }
        } finally {
            scope.cancel()
            temporary.deleteRecursively()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) } }
        }
    }

    private fun revealHelp(title: Int, body: Int) {
        val label = context.getString(title)
        scrollTo(label)
        assertFalse("Details initially collapsed", shown(context.getString(body)))
        click(label)
        instrumentation.waitForIdleSync()
        scrollTo(context.getString(body))
        await("Details visible on original production surface") { shown(context.getString(body)) }
    }
    private fun scrollTo(text: String) {
        repeat(30) {
            if (shown(text)) return
            val scrolled = nodes { all ->
                all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                    ?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 360f) == true
            }
            check(scrolled) { "No scrollable container for $text" }
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
        }
        error("Text did not become visible after scrolling: $text")
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
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { message }; Thread.sleep(50) }
    }
    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val directory = File(context.cacheDir, "ui-review/user-facing-copy").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-page-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
