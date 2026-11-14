package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.AiExtensionsPage
import dev.zakocoder.app.ui.WorkbenchMiuixTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated real app navigation, actual clicks and system day/night modes; no model or MCP calls. */
class AiSkillLoadingUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(25000) { while (!condition()) delay(60) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!node.refresh()) return@buildList
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(walk(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::walk)
    }
    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString()?.lines()?.contains(label) == true || it.contentDescription?.toString()?.lines()?.contains(label) == true) }
    private suspend fun click(label: String) {
        fun target(): AccessibilityNodeInfo? {
            val matches = nodes().filter { it.isVisibleToUser &&
                (it.text?.toString()?.lines()?.contains(label) == true ||
                    it.contentDescription?.toString()?.lines()?.contains(label) == true) }
            fun enabledClick(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isClickable && node.isEnabled &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            // Text and Switch may share a label while remaining siblings. Prefer the
            // actionable match before climbing ancestors of any descriptive text.
            fun descendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (enabledClick(node)) return node
                return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::descendant) }
            }
            matches.firstNotNullOfOrNull(::descendant)?.let { return it }
            return matches.firstNotNullOfOrNull { match ->
                var candidate = match.parent
                while (candidate != null) {
                    if (enabledClick(candidate)) break
                    candidate = candidate.parent
                }
                candidate
            }
        }
        // Some native controls handle pointer input without exposing ACTION_CLICK on
        // their merged wrapper. Fall back to a real tap only on the exact labelled,
        // visible and enabled control; subsequent assertions still verify the result.
        fun pointerTarget() = nodes().firstOrNull { it.isVisibleToUser && it.isEnabled &&
            it.contentDescription?.toString()?.lines()?.contains(label) == true &&
            android.graphics.Rect().also(it::getBoundsInScreen).let { bounds ->
                !bounds.isEmpty && bounds.width() <= context.resources.displayMetrics.widthPixels
            } }
        await("Enabled target: $label") { target() != null || pointerTarget() != null }
        val actionable = target()
        if (actionable != null) {
            assertTrue("Click $label", actionable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } else {
            val bounds = android.graphics.Rect().also(requireNotNull(pointerTarget())::getBoundsInScreen)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        delay(150)
    }
    private suspend fun search(value: String) {
        val label = context.getString(R.string.ai_skills_search)
        fun edit() = nodes().firstOrNull { node -> node.isVisibleToUser && node.isEditable &&
            (node.text?.toString()?.contains(label) == true || node.hintText?.toString()?.contains(label) == true ||
                (0 until node.childCount).any { node.getChild(it)?.text?.toString()?.contains(label) == true }) }
            ?: nodes().firstOrNull { it.isVisibleToUser && it.isEditable }
        await("Skill search editable") { edit() != null }
        assertTrue(requireNotNull(edit()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        delay(150)
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/skill-loading/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 150000) fun daySkillModeAndSelectionAreGlobalWithoutWorkspace() = runBlocking { exercise(false) }
    @Test(timeout = 150000) fun nightSkillModeAndSelectionAreGlobalWithoutWorkspace() = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = this.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val previousNight = withContext(Dispatchers.IO) { shell("cmd uimode night").substringAfter(":").trim() }
        check(previousNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
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
        val theme = if (dark) "night" else "day"
        try {
            withContext(Dispatchers.IO) { shell("cmd uimode night ${if (dark) "yes" else "no"}") }
            main { owned = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
            val workbench = requireNotNull(owned)
            await("Private workbench ready") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            withContext(Dispatchers.IO) { AiGlobalSkills(isolated).save(AiGlobalSkills.Selection(emptyList())) }
            main { workbench.ai.refreshGlobalSkills() }
            await("Global library ready without workspace") {
                !workbench.ai.globalSkills.value.skillsLoading && workbench.ai.globalSkills.value.skillsInitialized
            }
            assertTrue(workbench.state.value.projects.isEmpty())
            assertNull(workbench.ai.state.value.projectId)
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val state by workbench.state.collectAsStateWithLifecycle()
                    WorkbenchMiuixTheme(dark) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            AiExtensionsPage(workbench, state, initialTab = 0, standalone = true, close = {})
                        }
                    }
                } }
                instrumentation.waitForIdleSync()
                await("Global scope shown") { find(context.getString(R.string.extensions_global_scope)) != null }
                assertFalse(workbench.ai.globalSkills.value.skillOnDemand)
                assertTrue(workbench.ai.globalSkills.value.enabledSkills.isEmpty())
                screenshot("$theme-global-empty")
                search("env-audit")
                screenshot("$theme-filtered")
                click(context.getString(R.string.ai_skill_view, "env-audit"))
                await("Skill detail contains its readable instructions") { find("环境体检") != null }
                assertFalse("Skill frontmatter must not render as metadata headings or extra horizontal rules",
                    nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("name: env-audit") == true })
                screenshot("$theme-detail")
                shell("input keyevent 4")
                await("Skill detail closes to the filtered library") {
                    find(context.getString(R.string.ai_skill_view, "env-audit")) != null
                }
                click(context.getString(R.string.ai_skill_toggle, "env-audit"))
                await("Global selection applied") {
                    workbench.ai.globalSkills.value.enabledSkills == listOf("builtin:env-audit") &&
                        !workbench.ai.globalSkills.value.active
                }
                click(context.getString(R.string.ai_skill_on_demand))
                await("Global lazy mode saved") {
                    workbench.ai.globalSkills.value.skillOnDemand && !workbench.ai.globalSkills.value.active
                }
                screenshot("$theme-global-on-demand")
                // Workspace and historical conversation snapshots cannot replace global configuration.
                main { workbench.create("Skill 全局配置验证") }
                await("First workspace created") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
                val project = workbench.state.value.projects.single()
                val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
                withContext(Dispatchers.IO) {
                    fixture.history.save(project.id, AiState(projectId = project.id, conversationId = first, turn = 1,
                        skillsInitialized = true, enabledSkills = emptyList(), timeline = listOf(AiEntry("first", "旧空选择", role = AiRole.User))))
                    fixture.history.save(project.id, AiState(projectId = project.id, conversationId = second, turn = 1,
                        skillsInitialized = true, enabledSkills = AiBuiltinSkills.defaults, timeline = listOf(AiEntry("second", "旧推荐选择", role = AiRole.User))))
                    fixture.history.select(project.id, first)
                }
                main { workbench.ai.select(project.id) }
                suspend fun ready(id: String) = await("Historical conversation loaded: $id") {
                    workbench.ai.state.value.conversationId == id && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading
                }
                ready(first)
                for (conversation in listOf(second, first)) {
                    main { workbench.ai.conversation(conversation) }
                    ready(conversation)
                    assertEquals(listOf("builtin:env-audit"), workbench.ai.globalSkills.value.enabledSkills)
                    assertTrue(workbench.ai.globalSkills.value.skillOnDemand)
                }
                main { workbench.create("第二个工作区") }
                await("Second workspace created") { !workbench.state.value.busy && workbench.state.value.projects.size == 2 }
                val other = workbench.state.value.projects.single { it.id != project.id }
                main { workbench.ai.select(other.id) }
                await("Other workspace ready") {
                    workbench.ai.state.value.projectId == other.id && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading
                }
                assertEquals(listOf("builtin:env-audit"), workbench.ai.globalSkills.value.enabledSkills)
                assertTrue(workbench.ai.globalSkills.value.skillOnDemand)
                screenshot("$theme-global-after-workspace-switch")
                withContext(Dispatchers.IO) {
                    assertEquals(AiGlobalSkills.Selection(listOf("builtin:env-audit"), true), AiGlobalSkills(isolated).load())
                }
                assertTrue(workbench.ai.saveGlobalSkill("删除测试技能", "用于验证自定义技能删除", "仅用于本次隔离测试。", false, UUID.randomUUID().toString(), null))
                val custom = workbench.ai.globalSkills.value.skills.single { it.name == "删除测试技能" }
                search("删除测试技能")
                await("Custom delete entry appears") { find(context.getString(R.string.ai_skill_delete_named, custom.name)) != null }
                screenshot("$theme-custom-delete-entry")
                click(context.getString(R.string.ai_skill_delete_named, custom.name))
                await("Delete confirmation appears") { find(context.getString(R.string.ai_skill_delete_action)) != null }
                screenshot("$theme-custom-delete-confirm")
                click(context.getString(R.string.cancel))
                assertTrue("Cancel does not remove custom skill", workbench.ai.globalSkills.value.skills.any { it.path == custom.path })
                click(context.getString(R.string.ai_skill_view, custom.name))
                await("Custom detail supports deletion") { find(context.getString(R.string.ai_skill_delete)) != null }
                screenshot("$theme-custom-delete-detail")
                click(context.getString(R.string.ai_skill_delete))
                click(context.getString(R.string.ai_skill_delete_action))
                await("Confirmed delete removes skill and closes its detail") {
                    !workbench.ai.globalSkills.value.active && workbench.ai.globalSkills.value.skills.none { it.path == custom.path } &&
                        find(context.getString(R.string.ai_skills_empty)) != null
                }
                assertEquals(listOf("builtin:env-audit"), workbench.ai.globalSkills.value.enabledSkills)
                search("env-audit")
                assertNull("Built-in skill has no delete entry", find(context.getString(R.string.ai_skill_delete_named, "env-audit")))
                assertEquals(0, fixture.server.requestCount)
                assertFalse(workbench.ai.state.value.active)
                assertTrue(workbench.state.value.sessions.isEmpty())
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll() }
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    fixture.close()
                    synchronized(preferenceNames) { preferenceNames.forEach { context.deleteSharedPreferences(it) } }
                } finally { shell("cmd uimode night $previousNight") }
            }
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
}
