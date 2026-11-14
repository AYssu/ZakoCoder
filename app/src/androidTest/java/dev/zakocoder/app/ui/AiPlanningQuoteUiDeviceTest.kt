package dev.zakocoder.app.ui

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiCall
import dev.zakocoder.app.ai.AiGlobalSkills
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiPlanning
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.ai.AiTool
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual AiPage interactions and local TLS requests; no user projects, services, or model accounts. */
@SdkSuppress(minSdkVersion = 30)
class AiPlanningQuoteUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()

    @Before fun foreground() {
        check(context.packageName.endsWith(".runtimecheck")) { "Run this review only in the isolated runtimecheck application" }
        warmDeviceActivity()
    }

    @Test(timeout = 180000) fun dayAutomaticPlanningQuoteCancelEditAndSend() = runBlocking { review(false) }
    @Test(timeout = 180000) fun nightAutomaticPlanningQuoteCancelEditAndSend() = runBlocking { review(true) }

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, ready: () -> Boolean) = withTimeout(45000) {
        while (!ready()) delay(25)
        check(ready()) { label }
    }

    private suspend fun review(dark: Boolean) {
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val key = "${fixture.token}-$name"
                synchronized(preferences) { preferences.add(key) }
                return context.getSharedPreferences(key, mode)
            }
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        val theme = if (dark) "night" else "day"
        val original = "先分析界面结构，暂时不要改代码。"
        val followup = "沿用这条要求，补充一个简洁的实施计划。"
        try {
            main { owned = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
            val workbench = requireNotNull(owned)
            await("Isolated workbench ready") {
                !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading
            }
            withContext(Dispatchers.IO) { AiGlobalSkills(isolated).save(AiGlobalSkills.Selection(emptyList())) }
            main { workbench.ai.refreshGlobalSkills() }
            await("Global skill selection ready") { !workbench.ai.globalSkills.value.skillsLoading && workbench.ai.globalSkills.value.skillsInitialized }
            main { workbench.create("规划与引用界面验证") }
            await("Private workspace created") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            main { workbench.ai.select(project.id) }
            await("Conversation ready") {
                val state = workbench.ai.state.value
                state.projectId == project.id && state.configured && !state.restoring && !state.permissionsLoading &&
                    !state.permissionsSaving && !state.skillsLoading
            }
            // A deterministic local model fixture chooses the mode; this does not test live-model intent recognition.
            fixture.enqueue(calls = listOf(AiCall("seed-readonly", "set_task_mode", JSONObject()
                .put("mode", "readonly").put("user_request", original).toString())))
            fixture.enqueue("可以，先梳理界面层级，再列出调整步骤。")
            main { assertTrue(workbench.ai.send(original)) }
            await("Seed conversation completed through TLS") { !workbench.ai.state.value.active && workbench.ai.state.value.phase == AiPhase.Completed }
            assertEquals("", workbench.ai.state.value.error)
            assertTrue(workbench.ai.state.value.timeline.any { it.role == AiRole.User && it.text == original })
            repeat(2) { assertNotNull(withContext(Dispatchers.IO) { fixture.server.takeRequest(5, TimeUnit.SECONDS) }) }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val state by workbench.state.collectAsStateWithLifecycle()
                    WorkbenchMiuixTheme(dark) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            AiPage(workbench, state, onModels = {}, onProjects = {}, onTerminal = {})
                        }
                    }
                } }
                ui.await("Original turn and AI-selected planning state shown") { ui.has(original) && workbench.ai.state.value.planningMode }
                assertFalse("Mode selection must no longer occupy the composer", ui.has("执行"))
                assertTrue(ui.has(R.string.ai_planning_mode_hint))

                ui.click(original)
                ui.await("Message actions include quote") { ui.has(R.string.ai_quote_message) }
                ui.capture("ai-planning-quote/$theme-message-menu")
                ui.click(R.string.ai_quote_message)
                val preview = context.getString(R.string.ai_quote_preview, original)
                ui.await("Quoted message preview") { ui.has(preview) && ui.has(R.string.ai_quote_clear) }
                assertFalse("Quoting must not send a model request", workbench.ai.state.value.active)
                assertEquals(2, fixture.server.requestCount)
                ui.click(R.string.ai_quote_clear)
                ui.await("Cancel removes the quote without losing history") { !ui.has(R.string.ai_quote_clear) && ui.has(original) }
                assertEquals(2, fixture.server.requestCount)

                ui.click(original)
                ui.click(R.string.ai_quote_message)
                ui.await("Quoted message can be selected again") { ui.has(preview) }
                setComposer(followup)
                // Planning status belongs to the page header now; focusing the composer may move
                // it outside the viewport. Assert the active input and quote rather than header position.
                ui.await("New text and quote shown together") {
                    ui.has(preview) && composerText() == followup && workbench.ai.state.value.planningMode
                }
                ui.capture("ai-planning-quote/$theme-quoted-composer")
                val expected = context.getString(R.string.ai_quote_context, 1, context.getString(R.string.ai_quote_user), original, followup)
                fixture.enqueue(calls = listOf(AiCall("quote-readonly", "set_task_mode", JSONObject()
                    .put("mode", "readonly").put("user_request", expected).toString())))
                fixture.enqueue("实施计划：先核对现状，再做局部调整，最后运行定向验证。")
                ui.click(R.string.ai_send)
                await("Quoted planning task completes") { !workbench.ai.state.value.active && workbench.ai.state.value.phase == AiPhase.Completed && workbench.ai.state.value.turn == 2 }
                assertEquals("", workbench.ai.state.value.error)
                val firstQuotedRequest = JSONObject(requireNotNull(withContext(Dispatchers.IO) {
                    fixture.server.takeRequest(5, TimeUnit.SECONDS)
                }).body.readUtf8())
                assertTrue("A new direct user task defaults to execution before the model chooses readonly",
                    firstQuotedRequest.getJSONArray("tools").toString().contains("write_file"))
                val request = JSONObject(requireNotNull(withContext(Dispatchers.IO) {
                    fixture.server.takeRequest(5, TimeUnit.SECONDS)
                }).body.readUtf8())
                val messages = request.getJSONArray("messages")
                val quotedTasks = (0 until messages.length()).map { messages.getJSONObject(it) }
                    .filter { it.optString("role") == "user" && it.optString("content").contains(followup) }
                assertEquals("The new quoted task must occur exactly once", 1, quotedTasks.size)
                val sent = quotedTasks.single().getString("content")
                assertEquals("The transmitted task must preserve quoted text and the newly typed request", expected, sent)
                assertTrue(messages.toString().contains("Read-only planning mode"))
                val tools = request.getJSONArray("tools")
                assertTrue(tools.length() > 0)
                repeat(tools.length()) { index ->
                    val tool = AiTool.named(tools.getJSONObject(index).getJSONObject("function").getString("name"))
                    assertTrue("Quoted planning task must keep the read-only tool boundary", tool != null && AiPlanning.allows(tool))
                }
                assertEquals(4, fixture.server.requestCount)
                ui.await("Quote cleared after successful send") { !ui.has(R.string.ai_quote_clear) }
                ui.capture("ai-planning-quote/$theme-completed")
            }
        } finally {
            main { owned?.stopAll() }
            await("Owned task stopped") { owned?.ai?.state?.value?.active != true }
            withContext(Dispatchers.IO) {
                synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
                fixture.close()
            }
        }
    }

    private fun composerField(): SemanticsNode {
        val editable = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsActions.SetText) != null && node.boundsInWindow.let { it.width > 0 && it.height > 0 }) editable += node
            node.children.forEach(::collect)
        }
        fun visit(view: android.view.View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        return editable.distinctBy { it.id }.single()
    }

    private fun composerText(): String? {
        var text: String? = null
        instrumentation.runOnMainSync {
            text = composerField().config.getOrNull(SemanticsProperties.EditableText)?.text
        }
        return text
    }

    /** AiComposerDock currently exposes a single unlabelled BasicTextField; use its native SetText action. */
    private fun setComposer(value: String) {
        instrumentation.runOnMainSync {
            assertTrue(composerField().config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
    }
}
