package dev.zakocoder.app.ui

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.projects.Project
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@SdkSuppress(minSdkVersion = 30)
class ConversationSearchUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    @Test(timeout = 120000) fun daySearchLoadingFailureEmptyResultsAndOpen() = review(false)
    @Test(timeout = 120000) fun nightSearchLoadingFailureEmptyResultsAndOpen() = review(true)

    private fun review(dark: Boolean) {
        val opened = AtomicReference<Triple<String, String, String>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        ConversationSearchSheet(listOf(Project("fixture", "Search fixture", emptySet())), search = { query ->
                            delay(350)
                            when (query) {
                                "failure" -> throw java.io.IOException("fixture search failure")
                                "missing" -> AiConversationSearchResult(scannedConversations = 1)
                                else -> AiConversationSearchResult(listOf(
                                    AiConversationSearchHit("fixture", "conversation", "answer", "修复构建问题", 1000,
                                        AiConversationSearchKind.Assistant, "已经解决 Gradle 编译错误", 5, 11)), scannedConversations = 1)
                            }
                        }, onHit = { p, c, e -> opened.set(Triple(p, c, e)) }, onDismiss = {})
                    }
                }
            } }
            await("Empty search hint") { has(R.string.conversation_search_hint) }
            field("failure")
            await("Loading state") { has(R.string.conversation_search_loading) }
            await("Failure state") { has(R.string.conversation_search_failed) }
            field("missing")
            await("No results") { has(R.string.conversation_search_empty) }
            field("Gradle")
            await("Matched body") { nodes { all -> all.any { text(it).contains("已经解决 Gradle 编译错误") } } }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.cacheDir, "ui-review/conversation-search/${if (dark) "night" else "day"}.png").apply { parentFile!!.mkdirs() }
                    .outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { screenshot.recycle() }
            nodes { all ->
                var node: SemanticsNode? = all.first { text(it) == "修复构建问题" }
                while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertEquals(Triple("fixture", "conversation", "answer"), opened.get())
            field("")
            await("Cleared query resets results") { has(R.string.conversation_search_hint) }
            assertFalse(nodes { all -> all.any { text(it).contains("已经解决 Gradle 编译错误") } })
        }
    }

    private fun has(id: Int) = nodes { all -> all.any { text(it) == context.getString(id) } }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun field(value: String) {
        await("Search field") { nodes { all -> all.any { it.config.getOrNull(SemanticsActions.SetText) != null } } }
        nodes { all ->
            val node = all.last { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
    }
    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!ready()) { check(SystemClock.uptimeMillis() < deadline) { label }; SystemClock.sleep(40) }
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
}
