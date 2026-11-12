package dev.zakocoder.app

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import dev.zakocoder.app.settings.OnboardingRepository
import dev.zakocoder.app.settings.OnboardingProgress
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real native navigation with private stores; no provider credentials, guest processes or model calls. */
class AiExtensionsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(60) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString()?.lines()?.contains(label) == true)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private fun clickable(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val matches = node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString()?.lines()?.contains(label) == true
            if (node.isVisibleToUser && matches) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null) {
                    if (candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable &&
                        candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return candidate
                    candidate = candidate.parent
                }
            }
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private fun scrollFormForward(): Boolean {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        // Anchor to the fixed Save action's form window. Select the largest vertical
        // container, excluding nested editable fields and horizontal selectors.
        var candidate: AccessibilityNodeInfo? = null
        var area = 0L
        fun walk(node: AccessibilityNodeInfo) {
            val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
            if (node.isVisibleToUser && node.isScrollable && !node.isEditable &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } &&
                rect.height() > rect.width() / 2 && rect.width().toLong() * rect.height() > area) {
                candidate = node; area = rect.width().toLong() * rect.height()
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }
        fun containsSave(node: AccessibilityNodeInfo): Boolean =
            node.text?.toString() == context.getString(R.string.mcp_save) ||
                (0 until node.childCount).any { node.getChild(it)?.let(::containsSave) == true }
        roots.filter(::containsSave).forEach(::walk)
        return candidate?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
    }
    private suspend fun reveal(label: String) {
        repeat(12) {
            if (clickable(label) != null) return
            var largest: AccessibilityNodeInfo? = null
            var largestArea = 0L
            fun walk(node: AccessibilityNodeInfo) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val area = bounds.width().toLong() * bounds.height()
                if (node.isVisibleToUser && node.isScrollable && !node.isEditable && area > largestArea &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                    largest = node; largestArea = area
                }
                repeat(node.childCount) { node.getChild(it)?.let(::walk) }
            }
            instrumentation.uiAutomation.windows.mapNotNull { it.root }
                .filter { it.packageName?.toString() == context.packageName }.forEach(::walk)
            assertTrue("Scrollable target: $label", largest?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            delay(180)
        }
        assertNotNull("Revealed $label", clickable(label))
    }
    private fun control(label: String): AccessibilityNodeInfo = requireNotNull(clickable(label)) { "Clickable target: $label" }
    private suspend fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await("clickable $label") { node = clickable(label); node != null }
        assertTrue("Real click target: $label", requireNotNull(node).isClickable)
        assertTrue(label, requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(180)
    }
    private fun edit(label: String? = null): AccessibilityNodeInfo? {
        fun labelled(node: AccessibilityNodeInfo): Boolean = node.text?.toString()?.lines()?.contains(label.orEmpty()) == true || node.contentDescription?.toString()?.lines()?.contains(label.orEmpty()) == true || node.hintText?.toString()?.lines()?.contains(label.orEmpty()) == true || (0 until node.childCount).any { node.getChild(it)?.let(::labelled) == true }
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.isEditable && (label == null || labelled(node))) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun field(value: String, label: String? = null) {
        await("editable $label") { edit(label) != null }
        assertTrue(edit(label)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        delay(150)
    }
    private suspend fun screenshot(name: String) {
        delay(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/ui-refresh/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 180000) fun globalExtensionsAreAvailableFromDrawerWithoutWorkspaceInBothThemes() = runBlocking {
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = this.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        val preferenceNames = mutableSetOf<String>()
        val privateContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val key = "${fixture.token}-$name"
                synchronized(preferenceNames) { preferenceNames.add(key) }
                return context.getSharedPreferences(key, mode)
            }
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        try {
            withContext(Dispatchers.IO) {
                OnboardingRepository(privateContext).save(OnboardingProgress(completed = true))
            }
            main { owned = Workbench(privateContext, fixture.transport, fixture.config, AiPermissionStore(privateContext), fixture.history) }
            val workbench = requireNotNull(owned)
            await("Private workbench ready") {
                !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading
            }
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    val suffix = if (dark) "dark" else "light"
                    scenario.onActivity { activity -> activity.setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        CompositionLocalProvider(LocalConfiguration provides configuration) { ZakoCoderApp(workbench) }
                    } }
                    instrumentation.waitForIdleSync()
                    click(context.getString(R.string.app_navigation_open))
                    click(context.getString(R.string.ai_skills))
                    await("Global Skill scope") { find(context.getString(R.string.extensions_global_scope)) != null }
                    assertTrue(workbench.state.value.projects.isEmpty())
                    assertTrue(control(context.getString(R.string.ai_skill_add_custom)).isEnabled)
                    screenshot("global-skill-$suffix")
                    click(context.getString(R.string.ai_skill_add_custom))
                    await("Global Skill form without workspace") { edit(context.getString(R.string.ai_skill_custom_name)) != null }
                    screenshot("global-skill-editor-$suffix")
                    click(context.getString(R.string.close))
                    click(context.getString(R.string.ai_extensions_back))
                    click(context.getString(R.string.app_navigation_open))
                    click(context.getString(R.string.mcp_title))
                    await("Global MCP scope") { find(context.getString(R.string.extensions_global_scope)) != null }
                    screenshot("global-mcp-$suffix")
                    click(context.getString(R.string.mcp_design_add_server))
                    await("MCP URL editor") { edit(context.getString(R.string.mcp_url)) != null }
                    assertNotNull("HTTP is the default transport", find(context.getString(R.string.mcp_design_http)))
                    screenshot("global-mcp-editor-$suffix")
                    field("Fixture $suffix", context.getString(R.string.mcp_name))
                    field("http://localhost:12345/mcp", context.getString(R.string.mcp_url))
                    click(context.getString(R.string.mcp_save))
                    await("Saved without a workspace or connection") { workbench.mcp.state.value.servers.size == 1 && !workbench.mcp.state.value.busy }
                    val saved = workbench.mcp.state.value.servers.single()
                    assertEquals(dev.zakocoder.app.mcp.McpTransport.StreamableHttp, saved.transport)
                    assertFalse(saved.enabled)
                    assertTrue(workbench.state.value.projects.isEmpty())
                    screenshot("global-mcp-saved-$suffix")
                    click(context.getString(R.string.mcp_edit))
                    click(context.getString(R.string.mcp_design_protocol))
                    assertNotNull(find(context.getString(R.string.mcp_transport_http)))
                    assertNotNull(find(context.getString(R.string.mcp_transport_sse)))
                    click(context.getString(R.string.mcp_transport_sse))
                    click(context.getString(R.string.mcp_save))
                    await("SSE selection saved") { !workbench.mcp.state.value.busy && workbench.mcp.state.value.servers.single().transport == dev.zakocoder.app.mcp.McpTransport.Sse }
                    click(context.getString(R.string.mcp_delete)); click(context.getString(R.string.mcp_delete))
                    await("Fixture removed") { workbench.mcp.state.value.servers.isEmpty() && !workbench.mcp.state.value.busy }
                    click(context.getString(R.string.ai_extensions_back))
                    // Setup manages the same global library even before a workspace exists.
                    main { workbench.saveOnboardingStep(3) }
                    await("Setup step persisted") { workbench.state.value.onboarding.step == 3 }
                    scenario.onActivity { activity -> activity.setContent {
                        val state by workbench.state.collectAsStateWithLifecycle()
                        dev.zakocoder.app.ui.WorkbenchMiuixTheme(dark) {
                            dev.zakocoder.app.ui.OnboardingPage(workbench, state, onComplete = {})
                        }
                    } }
                    reveal(context.getString(R.string.setup_manage_skills))
                    click(context.getString(R.string.setup_manage_skills))
                    await("Setup opens global Skill library without workspace") {
                        find(context.getString(R.string.extensions_global_scope)) != null
                    }
                    screenshot("global-setup-skills-$suffix")
                    assertTrue(workbench.state.value.projects.isEmpty())
                    assertEquals(0, fixture.server.requestCount)
                    assertTrue(workbench.state.value.sessions.isEmpty())
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll() }
            withContext(NonCancellable + Dispatchers.IO) {
                fixture.close()
                synchronized(preferenceNames) { preferenceNames.forEach { context.deleteSharedPreferences(it) } }
            }
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
}
