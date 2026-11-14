package dev.zakocoder.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import dev.zakocoder.app.ui.UpgradeUiHarness
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual root navigation + real WebView + model fixture. No Ubuntu or user provider configuration. */
@SdkSuppress(minSdkVersion = 30)
class AgentBrowserApprovalUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = UpgradeUiHarness()
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(30_000) { while (!predicate()) delay(50) } }
        catch (e: TimeoutCancellationException) { throw AssertionError(label, e) }
    }
    private fun semantics(): List<Pair<String, List<String>>> {
        val result = java.util.concurrent.atomic.AtomicReference<List<Pair<String, List<String>>>>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(all.distinctBy { it.id }.filter { it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }.map {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { text -> text.text }.orEmpty() to
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            })
        }
        return result.get()
    }
    private suspend fun click(resource: Int) = withContext(Dispatchers.IO) { ui.click(resource) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private suspend fun capture(name: String) {
        delay(250)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/browser/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { image.recycle() }
    }
    @Test(timeout = 180_000) fun dayRootBrowserApprovalsPreserveDomAcrossApproveRejectAndStop(): Unit = runBlocking { exercise(false) }
    @Test(timeout = 180_000) fun nightRootBrowserApprovalsPreserveDomAcrossApproveRejectAndStop(): Unit = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
        val flags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val previousNight = withContext(Dispatchers.IO) { shell("cmd uimode night").substringAfter(':').trim() }
        check(previousNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
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
        try {
            withContext(Dispatchers.IO) { shell("cmd uimode night ${if (dark) "yes" else "no"}"); warmDeviceActivity() }
            main { owned = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
            val workbench = requireNotNull(owned)
            await("private workbench ready") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && workbench.capabilityPackages.state.value.loaded }
            main { workbench.completeOnboarding(); workbench.create("网页审批验收") }
            await("private workspace ready") { !workbench.state.value.busy && !workbench.state.value.onboarding.required && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            main { workbench.ai.select(project.id); workbench.capabilityPackages.setBrowserEnabled(true) }
            await("browser capability and assistant ready") {
                workbench.capabilityPackages.state.value.browserEnabled && !workbench.capabilityPackages.state.value.busy &&
                    workbench.ai.state.value.projectId == project.id && workbench.ai.state.value.configured &&
                    !workbench.ai.state.value.restoring && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading
            }
            main { workbench.ai.setPolicy(AiPolicy(overrides = emptyMap())) }
            await("explicit default Ask policy saved") { !workbench.ai.state.value.permissionsSaving }
            AgentBrowserDeviceTest.LocalSite().use { site ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent { ZakoCoderApp(workbench) } }
                    try {
                        await("assistant foreground") { ui.has(R.string.app_navigation_open) && ui.has(R.string.ai_more) }
                        for (decision in listOf("approve", "reject", "stop")) {
                            fun tool(id: String, args: JSONObject) = AiCall("$decision-$id", "browser", args.toString())
                            fixture.enqueue(calls = listOf(tool("navigate", JSONObject().put("action", "navigate").put("url", site.url))))
                            fixture.enqueue(calls = listOf(tool("type", JSONObject().put("action", "type").put("selector", "#name").put("text", "Kept DOM"))))
                            fixture.enqueue(calls = listOf(tool("save", JSONObject().put("action", "click").put("selector", "#save").put("wait_text", "Saved Kept DOM"))))
                            fixture.enqueue(calls = listOf(tool("read", JSONObject().put("action", "read"))))
                            if (decision != "stop") fixture.enqueue("Browser approval fixture finished", responseDelayMillis = 1000)
                            main { assertTrue(workbench.ai.send("Use the approved local preview and verify its form: $decision")) }
                            suspend fun approve(id: String) {
                                await("$decision $id pending") { workbench.ai.state.value.events.any { it.callId == "$decision-$id" && it.id == workbench.ai.state.value.approval?.eventId } }
                                click(R.string.ai_approve_once)
                            }
                            approve("navigate")
                            await("browser overlay attached") { workbench.browser.state.value.attached && workbench.browser.state.value.url == site.url }
                            approve("type")
                            approve("save")
                            await("read approval stays above browser") {
                                workbench.ai.state.value.events.any { it.callId == "$decision-read" && it.id == workbench.ai.state.value.approval?.eventId } &&
                                    ui.has(R.string.ai_approve_once)
                            }
                            assertEquals("Exactly one foreground approval host", 1, semantics().count { (text, descriptions) -> text == context.getString(R.string.ai_approve_once) || context.getString(R.string.ai_approve_once) in descriptions })
                            assertTrue(workbench.browser.state.value.attached)
                            assertTrue(workbench.browser.state.value.foreground)
                            assertEquals(site.url, workbench.browser.state.value.url)
                            assertTrue("DOM edits must survive successive permission dialogs",
                                workbench.browser.execute("read", JSONObject().put("selector", "#result")).getString("text").contains("Saved Kept DOM"))
                            capture("$theme-$decision-browser-approval")
                            click(when (decision) { "approve" -> R.string.ai_approve_once; "reject" -> R.string.ai_reject; else -> R.string.ai_stop })
                            await("$decision task finishes") { !workbench.ai.state.value.active }
                            val event = workbench.ai.state.value.events.single { it.callId == "$decision-read" }
                            assertEquals(when (decision) { "approve" -> AiToolStatus.Succeeded; "reject" -> AiToolStatus.Denied; else -> AiToolStatus.Cancelled }, event.status)
                            // Existing foreground contract remains: task completion returns to chat
                            // and destroys the browser; this test does not introduce background browsing.
                            await("task completion returns to conversation") { !workbench.browser.state.value.attached }
                            assertNull(workbench.ai.state.value.approval)
                        }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            runCatching { capture("$theme-failure") }
                            val app = workbench.state.value
                            val ai = workbench.ai.state.value
                            val diagnostic = JSONObject().put("failure", failure.message.orEmpty().take(300))
                                .put("app_loading", app.loading).put("app_busy", app.busy).put("setup_required", app.onboarding.required)
                                .put("workspace_count", app.projects.size).put("selected_id", app.selectedId ?: JSONObject.NULL)
                                .put("ai_project", ai.projectId ?: JSONObject.NULL).put("ai_configured", ai.configured)
                                .put("ai_restoring", ai.restoring).put("ai_permissions_loading", ai.permissionsLoading)
                                .put("ai_skills_loading", ai.skillsLoading).put("ai_active", ai.active).put("ai_phase", ai.phase.name)
                                .put("ai_history_error", ai.historyError).put("model_loading", workbench.ai.models.state.value.loading)
                                .put("browser_attached", workbench.browser.state.value.attached)
                            val visible = runCatching { semantics().take(160).map { (text, descriptions) ->
                                JSONObject().put("text", text.take(180)).put("descriptions", org.json.JSONArray(descriptions.map { it.take(180) }))
                            } }.getOrDefault(emptyList())
                            diagnostic.put("visible_semantics", org.json.JSONArray(visible))
                            withContext(Dispatchers.IO) {
                                File(context.cacheDir, "ui-review/browser/$theme-failure.json").apply { parentFile!!.mkdirs() }.writeText(diagnostic.toString(2))
                            }
                        }
                        throw failure
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll(); owned?.browser?.close() }
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    fixture.close()
                    synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
                } finally { shell("cmd uimode night $previousNight") }
            }
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { this.flags = flags }
        }
    }
}
