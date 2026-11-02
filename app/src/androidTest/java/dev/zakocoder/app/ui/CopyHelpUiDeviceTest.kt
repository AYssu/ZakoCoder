package dev.zakocoder.app.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolated presentation fixture: production CopyHelp and production copy, one topic at a time.
 * No real configuration, credentials, requests, projects or usage records are read or changed.
 * Screenshots establish help disclosure behavior and typography, not full-page acceptance.
 */
@SdkSuppress(minSdkVersion = 30)
class CopyHelpUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class Topic(val title: Int, val body: Int, val summary: Int, val screenshot: String? = null)
    private val topics = listOf(
        Topic(R.string.copy_ai_context_help_title, R.string.copy_ai_context_help, R.string.ai_options_context_hint, "context-expanded"),
        Topic(R.string.copy_ai_mcp_import_help_title, R.string.copy_ai_mcp_import_help, R.string.mcp_import_formats_hint, "mcp-expanded"),
        Topic(R.string.copy_ai_usage_help_title, R.string.copy_ai_usage_help, R.string.ai_usage_values_hint, "statistics-expanded"),
        Topic(R.string.copy_ai_thinking_help_title, R.string.copy_ai_thinking_help, R.string.ai_options_thinking_hint),
        Topic(R.string.copy_ai_headers_help_title, R.string.copy_ai_headers_help, R.string.ai_options_headers_hint),
        Topic(R.string.copy_ai_presets_help_title, R.string.copy_ai_presets_help, R.string.ai_context_presets_scope),
        Topic(R.string.copy_ai_protocol_help_title, R.string.copy_ai_protocol_help, R.string.ai_protocol_support_hint),
    )

    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 120000) fun day360Font130() = verify(false)
    @Test(timeout = 120000) fun night360Font130() = verify(true)

    private fun verify(dark: Boolean) {
        var selected by mutableIntStateOf(0)
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
                                val topic = topics[selected]
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(stringResource(topic.summary), style = MaterialTheme.typography.bodyMedium)
                                    CopyHelp(stringResource(topic.title), stringResource(topic.body))
                                }
                            }
                        }
                    }
                }
            } }
            topics.forEachIndexed { index, topic ->
                instrumentation.runOnMainSync { selected = index }
                val title = context.getString(topic.title)
                val body = context.getString(topic.body)
                await("Help title must be present") { shown(title) }
                assertFalse("Technical details must start collapsed: $title", shown(body))
                assertTrue("Short summary remains visible", shown(context.getString(topic.summary)))
                if (index == 0) capture(dark, "collapsed")
                click(title)
                await("Details must expand: $title") { shown(body) }
                when (topic.body) {
                    R.string.copy_ai_context_help -> {
                        assertTrue(body.contains("为输出预留空间"))
                        assertTrue(body.contains("90%"))
                        assertTrue(body.contains("损失部分细节"))
                    }
                    R.string.copy_ai_mcp_import_help -> {
                        assertTrue(body.contains("autoApprove"))
                        assertTrue(body.contains("不会自动授予权限"))
                    }
                    R.string.copy_ai_presets_help -> assertTrue(body.contains("2026 年 10 月 31 日"))
                }
                topic.screenshot?.let { capture(dark, it) }
                click(title)
                await("Details must collapse again: $title") { !shown(body) }
                assertTrue("Summary survives collapse", shown(context.getString(topic.summary)))
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
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { message }; Thread.sleep(50) }
    }
    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val directory = File(context.cacheDir, "ui-review/user-facing-copy").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-help-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
