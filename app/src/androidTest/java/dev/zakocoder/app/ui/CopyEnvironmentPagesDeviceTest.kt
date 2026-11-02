package dev.zakocoder.app.ui

import android.content.ContextWrapper
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
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.editor.lsp.EditorLspState
import dev.zakocoder.app.git.GitState
import dev.zakocoder.app.projects.Project
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real production components with isolated, explicit state fixtures; no install, command, Root or server calls. */
@SdkSuppress(minSdkVersion = 30)
class CopyEnvironmentPagesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun day360Font130() = render(false)
    @Test(timeout = 180000) fun night360Font130() = render(true)

    private fun render(dark: Boolean) = runBlocking {
        val token = "environment-copy-ui-${UUID.randomUUID()}"
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
        var page by mutableIntStateOf(0)
        val project = Project("copy-review", "移动端项目", setOf(ProjectKind.Android))
        val fixture = WorkbenchState(loading = false, projects = listOf(project), selectedId = project.id)
        try {
            await("Isolated workbench ready") { !workbench.state.value.loading }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    @Suppress("DEPRECATION")
                    activity.window.statusBarColor = if (dark) android.graphics.Color.rgb(11, 15, 20) else android.graphics.Color.rgb(245, 247, 250)
                    @Suppress("DEPRECATION")
                    activity.window.navigationBarColor = if (dark) android.graphics.Color.rgb(21, 27, 35) else android.graphics.Color.WHITE
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                    activity.setContent {
                        val config = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        CompositionLocalProvider(LocalConfiguration provides config,
                            LocalDensity provides Density(base.resources.displayMetrics.widthPixels / 360f, 1.3f),
                            LocalWorkbenchDark provides dark, LocalMinimumInteractiveComponentSize provides 48.dp) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                    key(page) {
                                        when (page) {
                                            0 -> EnvironmentPage(workbench, WorkbenchState(loading = false), {}, {})
                                            1 -> NewTerminalSheet(workbench, fixture, {})
                                            2 -> AddProjectSheet(true, {}, {}, {}, {}, {})
                                            3 -> GitWorkflowContent(GitState(projectId = project.id, repository = true,
                                                branch = "main", statusKnown = true), {}, GitWorkflowActions())
                                            4 -> EditorLspStatusContent(EditorLspState(), project.id, "README.md", {}, {}, enabled = false, onEnabledChanged = {})
                                            else -> AiValidationPanel(workbench, Modifier.fillMaxSize().padding(horizontal = 16.dp), {}, {})
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                await("Environment management") { shown(base.getString(R.string.maintenance_short)) }
                click(base.getString(R.string.maintenance_short))
                await("Offline package instructions") { shown(base.getString(R.string.offline_runtime_help)) }
                assertTrue(shown(base.getString(R.string.runtime_description)))
                assertFalse(base.getString(R.string.offline_runtime_help).contains("aarch64-pd"))
                capture(dark, "environment-management")

                instrumentation.runOnMainSync { page = 1 }
                await("New terminal shell description") { shown(base.getString(R.string.terminal_shell_help)) }
                assertTrue(base.getString(R.string.terminal_shell_help).contains("设置中选择"))
                capture(dark, "new-terminal")
                assertTrue(workbench.state.value.sessions.isEmpty())

                instrumentation.runOnMainSync { page = 2 }
                await("Project import sheet") { shown(base.getString(R.string.mobile_add_project)) }
                scrollUntil(base.getString(R.string.mobile_clone_hint), 350f)
                assertTrue(base.getString(R.string.mobile_clone_hint).contains("私有"))
                capture(dark, "add-project")

                instrumentation.runOnMainSync { page = 3 }
                await("Git page") { shown(base.getString(R.string.git_title)) }
                scrollUntil(base.getString(R.string.git_commit_help_title), 350f)
                assertFalse(shown(base.getString(R.string.git_commit_help_details)))
                click(base.getString(R.string.git_commit_help_title))
                await("Git hooks and signature limits") { shown(base.getString(R.string.git_commit_help_details)) }
                capture(dark, "git-commit-help")

                instrumentation.runOnMainSync { page = 4 }
                await("LSP disabled status") { shown(base.getString(R.string.editor_lsp_disabled)) }
                click(base.getString(R.string.editor_lsp_disabled))
                await("LSP opt in instructions") { shown(base.getString(R.string.editor_lsp_disabled_help)) }
                capture(dark, "editor-language-help")

                instrumentation.runOnMainSync { page = 5 }
                await("Validation summary") { shown(base.getString(R.string.project_validation_help)) }
                click(base.getString(R.string.project_validation_help_title))
                await("Validation details") { shown(base.getString(R.string.project_validation_help_details)) }
                capture(dark, "validation-help")
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
    private fun scrollUntil(text: String, amount: Float) {
        repeat(16) {
            if (shown(text)) return
            scroll(amount)
        }
        assertTrue("Visible text: $text", shown(text))
    }
    private fun scroll(amount: Float) {
        nodes { all -> all.lastOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
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
