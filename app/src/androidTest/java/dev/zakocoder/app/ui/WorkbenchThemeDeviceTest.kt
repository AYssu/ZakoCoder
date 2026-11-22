package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiProjectFiles
import dev.zakocoder.app.ai.AiSkills
import dev.zakocoder.app.terminal.TerminalItem
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Color regression coverage uses the real theme without the Surface that used to mask the bug. */
@SdkSuppress(minSdkVersion = 30)
class WorkbenchThemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()
    @Before fun foreground() = warmDeviceActivity()

    private fun textColors(label: String): List<Int> {
        val result = AtomicReference<List<Int>>()
        instrumentation.runOnMainSync {
            val colors = mutableListOf<Int>()
            fun collect(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                    node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    layouts.forEach { colors += it.layoutInput.style.color.toArgb() }
                }
                node.children.forEach(::collect)
            }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(colors)
        }
        return result.get()
    }
    private fun assertColor(label: String, expected: Int) {
        ui.reveal(label)
        ui.await("Text laid out: $label") { textColors(label).isNotEmpty() }
        textColors(label).forEach { actual -> assertEquals("Color for $label", expected, actual) }
    }
    private fun assertColor(id: Int, expected: Int) = assertColor(context.getString(id), expected)

    @Test(timeout = 90000) fun themeAndBothWindowHostsOwnTheirForeground() {
        var dark by mutableStateOf(true)
        var window by mutableIntStateOf(0)
        var expected by mutableStateOf(Color.Unspecified)
        var expectedScheme: ColorScheme? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(dark) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { expected = scheme.onSurface; expectedScheme = scheme }
                    when (window) {
                        0 -> Scaffold(containerColor = Color.Transparent) { padding ->
                            Column(Modifier.padding(padding)) {
                                Text("Root foreground")
                                Text("Secondary foreground", color = scheme.onSurfaceVariant)
                                Text("Error foreground", color = scheme.error)
                                Surface(color = scheme.primary, contentColor = scheme.onPrimary) { Text("Button foreground") }
                            }
                        }
                        // A window must not inherit the color of a triggering link/control.
                        1 -> CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                            WorkbenchSheet("Sheet", {}) { Text("Sheet foreground") }
                        }
                        2 -> CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                            WorkbenchPanelSheet({}) { Text("Panel foreground") }
                        }
                    }
                }
            } }
            for (night in listOf(true, false, true)) {
                instrumentation.runOnMainSync { dark = night; window = 0 }
                ui.await("Theme changed") { expected != Color.Unspecified &&
                    (ColorUtils.calculateLuminance(expected.toArgb()) > .5) == night }
                assertColor("Root foreground", expected.toArgb())
                assertColor("Secondary foreground", requireNotNull(expectedScheme).onSurfaceVariant.toArgb())
                assertColor("Error foreground", requireNotNull(expectedScheme).error.toArgb())
                assertColor("Button foreground", requireNotNull(expectedScheme).onPrimary.toArgb())
                for (which in 1..2) {
                    instrumentation.runOnMainSync { window = which }
                    assertColor(if (which == 1) "Sheet foreground" else "Panel foreground", expected.toArgb())
                }
            }
        }
    }

    @Test(timeout = 240000) fun affectedPagesAndNestedSheetsInBothThemes() {
        val token = "theme-${UUID.randomUUID()}"
        val root = File(context.cacheDir, token).apply { mkdirs() }
        val preferenceNames = mutableSetOf<String>()
        val base = context
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(
                "$token-$name".also { synchronized(preferenceNames) { preferenceNames += it } }, mode)
            override fun stopService(service: android.content.Intent) = false
        }
        isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
        val custom = AiSkills(AiProjectFiles { File(isolated.filesDir, "shared-skills") }).create("", "配色检查", "检查主题前景色", "检查文字可读性。")
        var host: Workbench? = null
        MockWebServer().use { server ->
            server.start()
            try {
                instrumentation.runOnMainSync { host = Workbench(isolated) }
                val workbench = requireNotNull(host)
                ui.await("Fresh workbench ready") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
                instrumentation.runOnMainSync { workbench.ai.refreshGlobalSkills() }
                ui.await("Skills loaded") { workbench.ai.globalSkills.value.skillsInitialized && !workbench.ai.globalSkills.value.skillsLoading }
                assertTrue(workbench.ai.models.state.value.catalog.profiles.isEmpty())
                // Construction alone does not start a shell/PTY. Preview only reads identity.
                val item = AtomicReference<TerminalItem>()
                instrumentation.runOnMainSync {
                    item.set(TerminalItem("theme-preview", "theme-workspace", "主题检查", TerminalSession(
                        "/system/bin/sh", root.absolutePath, arrayOf("sh"), emptyArray(), 100, null), 1))
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    for (dark in listOf(false, true)) {
                        var foreground = Color.Unspecified
                        var nativeForeground = Color.Unspecified
                        var secondary = Color.Unspecified
                        var background = Color.Unspecified
                        fun render(page: String) {
                            var composed = false
                            scenario.onActivity { activity ->
                                ui.configureSystemBars(activity, dark)
                                activity.setContent {
                                    WorkbenchMiuixTheme(dark) {
                                        val colors = MaterialTheme.colorScheme
                                        val preferenceTitle = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground
                                        SideEffect { foreground = colors.onSurface; nativeForeground = preferenceTitle; secondary = colors.onSurfaceVariant; background = colors.background; composed = true }
                                        Scaffold(containerColor = Color.Transparent) { padding ->
                                            when (page) {
                                                "skills", "mcp" -> AiExtensionsPage(workbench, workbench.state.collectAsState().value,
                                                    initialTab = if (page == "skills") 0 else 1, standalone = true, contentPadding = padding) {}
                                                "capabilities" -> CapabilityPackagesSheet(CapabilityPackagesUiModel(), {}, {}, {}, {}, {}, fullPage = true, contentPadding = padding)
                                                "memory" -> AgentMemoryPage(workbench.ai.memory, contentPadding = padding) {}
                                                "models" -> AiModelsPage(workbench.ai, contentPadding = padding) {}
                                                "editor" -> AiModelEditor(workbench.ai, null, contentPadding = padding) {}
                                                "transport" -> McpServerEditor(workbench.mcp, null, true) {}
                                                "preview" -> TerminalPreviewSheet(item.get(), { emptyList() }) {}
                                            }
                                        }
                                    }
                                }
                            }
                            instrumentation.waitForIdleSync()
                            ui.await("Page composed: $page") { composed }
                        }
                        fun capture(page: String) = ui.capture("dark-mode/${if (dark) "night" else "day"}-$page")
                        render("skills")
                        assertColor(R.string.ai_skills, foreground.toArgb())
                        val skills = workbench.ai.globalSkills.value
                        assertColor(context.getString(R.string.ai_skills_selected, skills.enabledSkills.size, AiSkills.MAX_ENABLED), foreground.toArgb())
                        assertColor(R.string.ai_skill_on_demand, nativeForeground.toArgb())
                        assertTrue("Native preference title remains readable", ColorUtils.calculateContrast(nativeForeground.toArgb(), background.toArgb()) >= 4.5)
                        assertColor(R.string.extensions_global_scope, secondary.toArgb())
                        capture("skills")
                        ui.setField(R.string.ai_skills_search, "配色检查")
                        assertColor(custom.name, nativeForeground.toArgb())
                        assertColor(custom.path, secondary.toArgb())
                        assertTrue("Custom skill path remains readable", ColorUtils.calculateContrast(secondary.toArgb(), background.toArgb()) >= 4.5)
                        capture("custom-skill")
                        ui.setField(R.string.ai_skills_search, "no-such-skill-theme-check")
                        assertColor(R.string.ai_skills_empty, foreground.toArgb())

                        render("mcp")
                        assertColor(R.string.mcp_title, foreground.toArgb())
                        assertColor(context.getString(R.string.mcp_configured, 0), foreground.toArgb())
                        capture("mcp")
                        ui.click(R.string.mcp_design_about)
                        assertColor(R.string.mcp_scope, foreground.toArgb())
                        capture("mcp-about")
                        render("transport")
                        ui.click(R.string.mcp_design_protocol)
                        val transportName = context.getString(R.string.mcp_transport_sse)
                        ui.reveal(transportName)
                        ui.await("Transport choice laid out") { textColors(transportName).isNotEmpty() }
                        // The Miuix choice and its summary may share a label; both text
                        // roles must follow this theme and retain readable contrast.
                        textColors(transportName).forEach { actual ->
                            assertTrue("Transport follows primary or summary text role",
                                actual == foreground.toArgb() || actual == nativeForeground.toArgb() || actual == secondary.toArgb())
                            assertTrue("Transport choice remains readable",
                                ColorUtils.calculateContrast(actual, background.toArgb()) >= 4.5)
                        }
                        capture("mcp-protocol")

                        render("capabilities")
                        assertColor(R.string.capability_packages_title, foreground.toArgb())
                        assertTrue("Heading contrast", ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
                        capture("capabilities")
                        render("memory")
                        assertColor(R.string.agent_memory_title, foreground.toArgb())
                        capture("memory")
                        render("models")
                        assertColor(R.string.ai_models_empty, foreground.toArgb())
                        capture("models-empty")
                        render("editor")
                        ui.setField(R.string.ai_endpoint, server.url("/v1").toString())
                        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"theme-check-model\"}]}"))
                        ui.click(R.string.ai_models_fetch)
                        assertColor("theme-check-model", foreground.toArgb())
                        capture("model-list")
                        render("preview")
                        assertColor(R.string.terminal_preview, foreground.toArgb())
                        assertColor(context.getString(R.string.terminal_preview_source, 1, "主题检查"), foreground.toArgb())
                        assertColor(R.string.terminal_preview_candidates, foreground.toArgb())
                        assertColor(R.string.terminal_preview_empty, foreground.toArgb())
                        capture("terminal-preview")
                    }
                }
                assertEquals("Only explicit loopback model discovery; no model or MCP calls", 2, server.requestCount)
                assertEquals("Preview must never create a process", 0, item.get().session.pid)
            } finally {
                instrumentation.runOnMainSync { host?.editor?.dispose(); host?.stopAll() }
                SystemClock.sleep(300)
                root.deleteRecursively()
                synchronized(preferenceNames) { preferenceNames.toList() }.forEach(base::deleteSharedPreferences)
            }
        }
    }
}
