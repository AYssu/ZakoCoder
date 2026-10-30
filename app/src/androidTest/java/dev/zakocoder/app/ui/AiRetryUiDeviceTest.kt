package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiRequestLog
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Synthetic UI state only: no transport, credentials, network or user history. */
@SdkSuppress(minSdkVersion = 30)
class AiRetryUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day360Font130() = render(false)
    @Test fun night360Font130() = render(true)

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
    private fun has(label: String) = nodes { all -> all.any { text(it) == label } }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { label }
            Thread.sleep(50)
        }
    }
    private fun click(label: String) {
        await("control: $label") { has(label) }
        nodes { all ->
            var node = all.last { text(it) == label } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }

    private fun render(dark: Boolean) {
        val notice = context.getString(R.string.ai_retry_wait, 2, 3, 1200L)
        val attemptText = context.getString(R.string.ai_request_retry_attempt, 2, 3, 1200L)
        val groupText = context.getString(R.string.ai_request_retry_group, "retry-review", "idle_timeout")
        val request = AiRequestLog(id = "retry-attempt-2", turn = 1, round = 1,
            model = "review-model", origin = "https://example.invalid", elapsed = 850,
            status = 200, complete = true, finish = "stop", inputTokens = 4800, outputTokens = 320,
            retryGroup = "retry-review", attempt = 2, maxAttempts = 3, retryDelayMs = 1200,
            retryReason = "idle_timeout")
        val committed = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = LocalConfiguration.current
                val widthPx = LocalWindowInfo.current.containerSize.width
                val configuration = remember(current, dark) { Configuration(current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                } }
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
                    LocalDensity provides Density(widthPx.coerceAtLeast(1) / 360f, 1.3f)) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AiRetryStatus(notice)
                                AiRequestsPanel(AiState(conversationId = "retry-review", requests = listOf(request)), Modifier.weight(1f))
                            }
                        }
                    }
                }
                SideEffect { committed.set(true) }
            } }
            await("retry fixture composition committed") { committed.get() && has(notice) }
            capture(dark, "retrying")
            click(context.getString(R.string.ai_request_row, 1, 1))
            await("retry details committed") { has(attemptText) && has(groupText) }
            // Keep the attempt and previous interruption reason visible in the actual details view.
            nodes { all ->
                val node = all.last { text(it) == groupText }
                var scroll = node.parent
                while (scroll != null && scroll.config.getOrNull(SemanticsActions.ScrollBy) == null) scroll = scroll.parent
                requireNotNull(scroll).config[SemanticsActions.ScrollBy].action?.invoke(0f, 80f)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            nodes { all ->
                for (label in listOf(attemptText, groupText)) {
                    val node = all.last { text(it) == label }
                    var root = node
                    while (root.parent != null) root = requireNotNull(root.parent)
                    val y = node.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                    val top = root.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                    assertTrue("retry metadata visible", y >= top && y + node.layoutInfo.coordinates.size.height <= top + root.layoutInfo.coordinates.size.height)
                }
            }
            capture(dark, "retry-request")
        }
    }

    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(450)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/request-retry/${if (dark) "night" else "day"}-$name.png")
                .apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
