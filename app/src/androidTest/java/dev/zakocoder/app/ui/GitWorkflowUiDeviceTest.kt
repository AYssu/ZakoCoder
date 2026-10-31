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
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.git.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Production Git composables, synthetic repository state, no network/process/user repository access. */
@SdkSuppress(minSdkVersion = 30)
class GitWorkflowUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day360Font130() = render(false)
    @Test fun night360Font130() = render(true)
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val found = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { found += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(found))
        }
        return result.get()
    }
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun visible(text: String) = nodes { all -> all.any { label(it) == text && it.boundsInWindow.width > 0 && it.boundsInWindow.height >= it.size.height * .9f } }
    private fun await(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!visible(text)) { check(SystemClock.uptimeMillis() < deadline) { "Not visible: $text" }; Thread.sleep(50) }
    }
    private fun click(resource: Int) {
        val value = context.getString(resource)
        nodes { all ->
            var node = all.last { label(it) == value || value in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        settle()
    }
    private fun scrollTo(resource: Int) {
        val text = context.getString(resource)
        nodes { all -> all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, -10000f) }
        settle()
        repeat(8) {
            if (visible(text)) return
            nodes { all ->
                val scroll = all.first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 600f)
            }
            settle()
        }
        assertTrue(visible(text))
    }
    private fun render(dark: Boolean) {
        val baseline = GitState(projectId = "review", repository = true, statusKnown = true, branch = "feature/mobile-git", upstream = "origin/feature/mobile-git", ahead = 3, behind = 2,
            remotes = listOf(GitRemote("origin", "https://git.example.invalid/team/mobile-game.git")),
            branches = listOf("main", "feature/mobile-git", "fix/build-config"),
            entries = listOf(GitEntry("app/src/main/MainActivity.kt", 'M', ' '), GitEntry("README.md", ' ', 'M')))
        val current = mutableStateOf(baseline)
        val events = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                GitWorkflowContent(current.value, {}, GitWorkflowActions(
                    fetch = { events += "fetch:$it" }, pull = { r, m -> events += "pull:$r:$m" }, push = { r, u -> events += "push:$r:$u" },
                    openFile = { events += "open:$it" }, resolve = { events += "resolve:${it.single()}" }, stop = { events += "stop" }, credentials = { url -> events += "credentials:$url" }))
            } } }
            await(context.getString(R.string.git_workflow_fetch)); capture(dark, "overview")
            click(R.string.git_workflow_fetch); assertEquals("fetch:origin", events.last())
            click(R.string.git_workflow_pull); capture(dark, "pull-confirm")
            click(R.string.git_workflow_confirm); assertEquals("pull:origin:false", events.last())
            click(R.string.git_workflow_push); capture(dark, "push-confirm")
            click(R.string.git_workflow_confirm); assertEquals("push:origin:false", events.last())
            click(R.string.git_workflow_branches); capture(dark, "branches")
            click(R.string.close)
            click(R.string.git_commit); await(context.getString(R.string.git_message)); capture(dark, "commit")
            click(R.string.close)
            instrumentation.runOnMainSync { current.value = baseline.copy(merging = true, conflicts = listOf("app/src/main/MainActivity.kt"), error = "Merge conflict", failure = GitFailure.Conflict, output = "CONFLICT (content): Merge conflict in app/src/main/MainActivity.kt") }
            scrollTo(R.string.git_workflow_resolve); capture(dark, "conflicts")
            click(R.string.git_workflow_resolve); capture(dark, "resolve-confirm")
            click(R.string.git_workflow_confirm); assertTrue(events.last().startsWith("resolve:"))
            instrumentation.runOnMainSync { current.value = baseline.copy(busy = true, operation = "fetch") }
            await(context.getString(R.string.git_workflow_cancel)); capture(dark, "running")
            click(R.string.git_workflow_cancel); assertEquals("stop", events.last())
            instrumentation.runOnMainSync { current.value = baseline.copy(error = "Authentication rejected", failure = GitFailure.Authentication, output = "Authentication failed for HTTPS repository.") }
            scrollTo(R.string.git_workflow_failed); capture(dark, "failure")
            // Already sanitized, deterministic output fixture; no credential or real repository reads.
            val longOutput = buildString {
                appendLine("remote: Authorization: Bearer [REDACTED]")
                repeat(24) { index -> appendLine("remote: object ${index + 1}/24 app/src/main/mobile_game_component_${index + 1}.kt verified") }
                append("Fetch completed; upstream refs updated.")
            }
            instrumentation.runOnMainSync { current.value = baseline.copy(output = longOutput) }
            scrollTo(R.string.git_workflow_output)
            // The title can be visible while the first output line is still below the viewport.
            // Scroll the vertical repository list until a meaningful log region is exposed.
            repeat(10) {
                val exposed = nodes { all -> all.any { label(it) == longOutput && it.boundsInWindow.height > 200 } }
                if (!exposed) {
                    nodes { all -> all.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                        .config[SemanticsActions.ScrollBy].action?.invoke(0f, 240f) }
                    settle()
                }
            }
            nodes { all ->
                val output = all.single { label(it) == longOutput }
                assertTrue("Long output must occupy the visible log viewport: ${output.boundsInWindow}", output.boundsInWindow.width > 0 && output.boundsInWindow.height > 200)
                assertTrue(label(output).contains("[REDACTED]"))
                listOf(R.string.git_stage, R.string.git_commit).forEach { resource ->
                    val target = all.last { label(it) == context.getString(resource) }
                    assertTrue("Fixed footer action must remain fully visible: $resource", target.boundsInWindow.width > 0 && target.boundsInWindow.height >= target.size.height * .99f)
                    assertTrue("Log must not cover fixed footer", output.boundsInWindow.bottom <= target.boundsInWindow.top)
                }
            }
            capture(dark, "long-output")
            instrumentation.runOnMainSync { current.value = baseline.copy(failure = GitFailure.Cancelled, error = "Cancelled", merging = true, conflicts = listOf("app/src/main/MainActivity.kt")) }
            scrollTo(R.string.git_workflow_failed); capture(dark, "cancelled-rechecked")
            instrumentation.runOnMainSync { current.value = baseline.copy(repository = false, statusKnown = false, branch = "", upstream = "", remotes = emptyList(), entries = emptyList(), failure = GitFailure.TimedOut, error = "Timed out") }
            scrollTo(R.string.git_workflow_failed); capture(dark, "timeout-unknown")
            instrumentation.runOnMainSync { current.value = baseline.copy(remotes = listOf(GitRemote("origin", "ssh://git@git.example.invalid/team/repository.git"))) }
            scrollTo(R.string.git_workflow_ssh_credentials); capture(dark, "ssh-auth-guidance")
            nodes { all -> assertFalse(all.any { label(it) == context.getString(R.string.git_workflow_credentials) }) }
        }
    }
    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
        val width = LocalWindowInfo.current.containerSize.width
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark, LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun capture(dark: Boolean, name: String) {
        settle()
        val dir = File(context.cacheDir, "ui-review/git-workflow").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(dir, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
