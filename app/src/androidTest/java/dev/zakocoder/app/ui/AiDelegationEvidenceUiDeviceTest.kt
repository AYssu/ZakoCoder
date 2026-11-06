package dev.zakocoder.app.ui

import android.content.ContextWrapper
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
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** PR evidence: actual concurrent controller execution rendered by existing product components. */
@SdkSuppress(minSdkVersion = 30)
class AiDelegationEvidenceUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test(timeout = 90000) fun day360Font130() = render(false)
    @Test(timeout = 90000) fun night360Font130() = render(true)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { label }; Thread.sleep(25) }
    }
    private fun textsAndClick(clickTool: Boolean = false): String {
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("\n") { it.text }
            if (clickTool) {
                var node: SemanticsNode? = all.last { text(it) == context.getString(AiTool.DelegateReadOnly.label) }
                while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            result.set(all.joinToString("\n", transform = ::text))
        }
        return result.get()
    }
    private fun capture(dark: Boolean, name: String) {
        Thread.sleep(300); instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/agent-delegation/${if (dark) "night" else "day"}-$name.png").apply {
                parentFile!!.mkdirs(); outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
    private fun render(dark: Boolean) {
        warmDeviceActivity()
        AiDeviceTest.Fixture(context, timeoutSeconds = 30, readTimeoutSeconds = 25).use { f ->
            val project = File(f.root, "project").apply { mkdirs() }
            File(project, "answer.txt").writeText("Public test fixture: read-only evidence.\n")
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            val ai = AtomicReference<AiController>()
            val diagnostics = mutableStateOf(false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        val controller = AiController(isolated, { project }, { _, _ -> false }, { _, _ -> error("No commands") },
                            {}, {}, f.transport, f.config, history = f.history)
                        ai.set(controller); controller.select("delegation-evidence")
                        activity.setContent {
                            val width = LocalWindowInfo.current.containerSize.width
                            CompositionLocalProvider(LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f), LocalWorkbenchDark provides dark) {
                                MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                    val state by controller.state.collectAsState()
                                    Surface(Modifier.fillMaxSize()) {
                                        if (diagnostics.value) Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                                            Text(context.getString(R.string.ai_request_details), style = MaterialTheme.typography.titleLarge)
                                            AiRequestsPanel(state, Modifier.weight(1f))
                                        } else Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                            state.events.firstOrNull { it.tool == AiTool.DelegateReadOnly }?.let { AiToolCard(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    await("controller ready") { ai.get().state.value.let { it.configured && !it.skillsLoading && !it.permissionsLoading && !it.restoring } }
                    val tasks = JSONArray(listOf("scout", "reviewer").map { JSONObject().put("role", it).put("task", "Inspect answer.txt; report evidence.") })
                    f.enqueue(calls = listOf(AiCall("delegate", "delegate_readonly", JSONObject().put("tasks", tasks).toString())))
                    repeat(2) { f.enqueue(calls = listOf(AiCall("read-$it", "read_file", "{\"path\":\"answer.txt\"}"))) }
                    repeat(2) { f.enqueue("answer.txt:1 contains public test evidence.", responseDelayMillis = 8000) }
                    f.enqueue("Both assistants returned read-only findings.")
                    instrumentation.runOnMainSync { ai.get().setAutomaticDelegation(true); assertTrue(ai.get().send("Find and review answer.txt in parallel.")) }
                    await("both children running independently") { f.server.requestCount == 5 && ai.get().state.value.events.count { it.tool == AiTool.ReadFile && it.status == AiToolStatus.Succeeded } == 2 }
                    await("tool card rendered") { textsAndClick().contains(context.getString(AiTool.DelegateReadOnly.label)) }
                    textsAndClick(clickTool = true)
                    await("both progress rows visible") { textsAndClick().let { "1:scout" in it && "2:reviewer" in it } }
                    capture(dark, "running")
                    instrumentation.runOnMainSync { diagnostics.value = true }
                    await("both child request identities visible") { textsAndClick().let { "1:scout" in it && "2:reviewer" in it } }
                    capture(dark, "requests")
                    await("parent completed") { !ai.get().state.value.active }
                    assertEquals(AiPhase.Completed, ai.get().state.value.phase)
                    assertEquals(6, f.server.requestCount)
                    assertEquals(3, ai.get().state.value.taskUsage.tools)
                } finally { instrumentation.runOnMainSync { ai.get()?.stop() } }
            }
        }
    }
}
