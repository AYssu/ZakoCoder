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
import dev.zakocoder.app.ui.ZakoCoderApp
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
            matches.firstOrNull(::enabledClick)?.let { return it }
            return matches.firstNotNullOfOrNull { match ->
                var candidate = match.parent
                while (candidate != null) {
                    if (enabledClick(candidate)) break
                    candidate = candidate.parent
                }
                candidate
            }
        }
        await("Enabled target: $label") { target() != null }
        assertTrue("Click $label", requireNotNull(target()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
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

    @Test(timeout = 150000) fun daySkillModeAndSelectionRemainConversationScoped() = runBlocking { exercise(false) }
    @Test(timeout = 150000) fun nightSkillModeAndSelectionRemainConversationScoped() = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck"))
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
            main { workbench.create("Skill 加载验收") }
            await("Owned project ready") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
            val firstTitle = "按需加载会话"; val secondTitle = "整篇加载会话"
            withContext(Dispatchers.IO) {
                fixture.history.save(project.id, AiState(projectId = project.id, conversationId = first, turn = 1,
                    skillsInitialized = true, enabledSkills = emptyList(), timeline = listOf(AiEntry("first", firstTitle, role = AiRole.User))))
                fixture.history.save(project.id, AiState(projectId = project.id, conversationId = second, turn = 1,
                    skillsInitialized = true, enabledSkills = AiBuiltinSkills.defaults, timeline = listOf(AiEntry("second", secondTitle, role = AiRole.User))))
                fixture.history.select(project.id, first)
            }
            main { workbench.ai.select(project.id) }
            suspend fun ready(id: String) = await("Selected conversation ready") {
                workbench.ai.state.value.conversationId == id && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading
            }
            ready(first)
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { ZakoCoderApp(workbench) } }
                instrumentation.waitForIdleSync()
                if (find(context.getString(R.string.nav_ai)) == null) click(context.getString(R.string.app_navigation_open))
                click(context.getString(R.string.nav_ai))
                click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_extensions))
                await("Skill panel identity") { find(firstTitle) != null }
                assertFalse("Existing conversations default to eager bodies", workbench.ai.state.value.skillOnDemand)
                screenshot("$theme-default-eager")
                search("frontend-design")
                click(context.getString(R.string.ai_skill_toggle, "frontend-design"))
                await("Selection applied") { workbench.ai.state.value.enabledSkills == listOf("builtin:frontend-design") }
                click(context.getString(R.string.ai_skill_on_demand))
                await("Lazy mode selected") { workbench.ai.state.value.skillOnDemand }
                screenshot("$theme-on-demand-selected")
                // Both switches below are actual UI conversation picker selections.
                click(firstTitle); click(secondTitle); ready(second)
                assertFalse("Other conversation retains eager mode", workbench.ai.state.value.skillOnDemand)
                assertEquals(AiBuiltinSkills.defaults, workbench.ai.state.value.enabledSkills)
                screenshot("$theme-second-eager")
                click(secondTitle); click(firstTitle); ready(first)
                assertTrue("First conversation restores lazy mode", workbench.ai.state.value.skillOnDemand)
                assertEquals(listOf("builtin:frontend-design"), workbench.ai.state.value.enabledSkills)
                screenshot("$theme-first-restored")
                click(context.getString(R.string.ai_extensions_back))
                assertEquals(0, fixture.server.requestCount)
                assertFalse(workbench.ai.state.value.active)
                assertTrue(workbench.state.value.sessions.isEmpty())
                withContext(Dispatchers.IO) {
                    assertTrue(fixture.history.load(project.id, first).state!!.skillOnDemand)
                    assertEquals(listOf("builtin:frontend-design"), fixture.history.load(project.id, first).state!!.enabledSkills)
                    assertFalse(fixture.history.load(project.id, second).state!!.skillOnDemand)
                }
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
