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
import java.util.concurrent.atomic.AtomicReference

@SdkSuppress(minSdkVersion = 30)
class AiChatActionsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 90000) fun day360Font130() = render(false)
    @Test(timeout = 90000) fun night360Font130() = render(true)
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
    private fun click(label: String) = nodes { all ->
        var node: SemanticsNode? = all.last { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    private fun render(dark: Boolean) {
        val entry = AiEntry("review-user", "请帮我检查项目，并运行相关测试。", role = AiRole.User, turn = 1)
        val state = AiState(turn = 1, turnTimings = listOf(AiTurnTiming(1, 1793480400000L, 1793480804000L)),
            requests = listOf(AiRequestLog(turn = 1, round = 1, inputTokens = 160000, outputTokens = 3000,
                cachedTokens = 128000, elapsed = 62000, firstEvent = 2000)))
        var dialog by mutableStateOf(false)
        var edit by mutableStateOf(false)
        var confirmed by mutableStateOf(false)
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
                            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("编码助手", style = MaterialTheme.typography.titleLarge)
                                AiMessage(entry, "review", {}, {}, onEdit = { edit = true; dialog = true },
                                    onRetry = { edit = false; dialog = true }, onRequests = {})
                                AiMessage(AiEntry("review-answer", "已检查当前改动。相关测试已执行，建议核对文件差异后再提交。", role = AiRole.Assistant, turn = 1), "review", {}, {})
                                AiTurnFooter(state, 1)
                            }
                            if (dialog) AiMessageRetryDialog(entry, edit, { dialog = false }, { confirmed = true; dialog = false })
                        }
                    }
                }
            } }
            val input = context.getString(R.string.ai_usage_input, "↑", "160K")
            val output = context.getString(R.string.ai_usage_output, "↓", "3K")
            val total = context.getString(R.string.ai_usage_total, "Σ", "163K")
            await("footer reaches reported totals") { nodes { all -> listOf(input, output, total).all { expected -> all.any { text(it) == expected } } } }
            nodes { all ->
                val first = all.single { text(it) == input }.boundsInRoot
                val out = all.single { text(it) == output }.boundsInRoot
                val sum = all.single { text(it) == total }.boundsInRoot
                assertEquals(first.top, out.top, 1f); assertEquals(first.top, sum.top, 1f)
                val details = all.single { text(it).contains("token/s") }.boundsInRoot
                assertTrue(details.top >= first.bottom)
                assertTrue(all.any { text(it).contains("缓存 128K（80%）") })
            }
            capture(dark, "footer")
            click(entry.text)
            await("message menu opens") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_message_retry) } } }
            assertFalse(confirmed)
            capture(dark, "actions")
            click(context.getString(R.string.ai_message_edit))
            await("edit dialog opens") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_message_retry_notice) } } }
            capture(dark, "edit")
            click(context.getString(R.string.cancel))
            await("edit dialog dismissed") { nodes { all -> all.none { text(it) == context.getString(R.string.ai_message_retry_notice) } } }
            assertFalse("Opening or cancelling must not resend", confirmed)
            click(entry.text)
            await("message menu reopens") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_message_edit) } } }
            click(context.getString(R.string.ai_message_retry))
            await("explicit retry confirmation") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_message_retry_title) } } }
            assertFalse(confirmed)
            capture(dark, "retry")
            click(context.getString(R.string.ai_message_retry))
            assertTrue(confirmed)
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
        assertTrue("Two native draw frames before screenshot", frames.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        // Semantics can be published before RenderThread submits the matching image buffer.
        Thread.sleep(250)
        val phase = if (InstrumentationRegistry.getArguments().getString("readingReviewStage") == "before") "before" else "after"
        val root = File(context.cacheDir, "ui-review/chat-actions").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
