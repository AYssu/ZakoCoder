package dev.zakocoder.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.*
import dev.zakocoder.app.workflow.ProjectCommand
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native UI fixtures only; never sends a model request or reads provider settings. */
class AiPlanUiDeviceTest {
    @Before fun foregroundForDeviceUi() { warmDeviceActivity() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val host get() = (context.applicationContext as ZakoApplication).workbench
    private val steps = listOf("读取项目规则、金额逻辑和服务入口", "提交金额与标题的最小修改供审批", "执行配置测试、构建并通过可见终端验证网页与 /quote")
        .map { AiPlanStep(it, "completed") }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(70) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun windows() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun click(label: String) {
        val details = listOf(R.string.ai_requests, R.string.project_validation).map(context::getString)
        if (label in details) {
            if (find(context.getString(R.string.close)) != null) click(context.getString(R.string.close))
            click(context.getString(R.string.ai_more))
        }
        await("visible $label") { find(label) != null }
        var node = find(label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(label, node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); delay(300)
    }
    private fun bounds(label: String) = Rect().also { find(label)!!.getBoundsInScreen(it) }
    private fun editable(): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.isEditable) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try { withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "plan"
            File(context.cacheDir, "ui-review/$theme/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { bitmap.recycle() }
    }

    @Test fun planHistoryIsBoundedOptionalAndDoesNotRestartTask() = runBlocking {
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, "plan-history-${UUID.randomUUID()}")
            try {
                val history = AiHistoryStore(root)
                val original = AiState(turn = 3, active = true, phase = AiPhase.Requesting, plan = steps,
                    timeline = listOf(AiEntry("own-message", "PLAN_HISTORY_FIXTURE", role = AiRole.User, turn = 3)))
                history.save("fixture", original)
                val restored = AiHistoryStore(root).load("fixture").state!!
                assertEquals(steps, restored.plan); assertEquals(original.timeline, restored.timeline)
                assertFalse(restored.active); assertTrue(restored.interrupted); assertEquals(AiPhase.Cancelled, restored.phase)
                val file = root.walkTopDown().single { it.name == "${original.conversationId}.json" }
                val json = JSONObject(file.readText())
                json.remove("plan"); file.writeText(json.toString())
                val legacy = AiHistoryStore(root).load("fixture").state!!
                assertTrue(legacy.plan.isEmpty()); assertEquals(original.timeline, legacy.timeline); assertEquals(3, legacy.turn)
                val tooMany = JSONArray()
                repeat(11) { tooMany.put(JSONObject().put("title", "step").put("status", "pending")) }
                json.put("plan", tooMany); file.writeText(json.toString())
                assertTrue(runCatching { AiHistoryStore(root).load("fixture") }.exceptionOrNull() is IllegalArgumentException)
            } finally { root.deleteRecursively() }
        }
    }

    @Test(timeout = 120000) fun activeDefaultsManualChoicesUpdatesKeyboardAndTaskIdentity() = runBlocking {
        windows()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("idle host") { !host.state.value.loading && !host.ai.state.value.active }
            var state by mutableStateOf(AiState(projectId = "fixture-a", conversationId = UUID.randomUUID().toString(), turn = 1, active = true, plan = steps))
            var panel by mutableIntStateOf(0)
            var visible by mutableStateOf(true)
            scenario.onActivity { it.setContent {
                val dark = isSystemInDarkTheme()
                MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column { AiTaskPlanPanel(state, panel, visible) }
                    }
                }
            } }
            val title = context.getString(R.string.ai_current_plan)
            val notice = context.getString(R.string.ai_plan_reported)
            await("active process defaults expanded") { find(notice) != null }
            click(title)
            assertNull(find(notice))
            main { state = state.copy(plan = steps.map { it.copy(status = "pending") }) }
            delay(200); assertNull("Plan updates preserve an explicit fold", find(notice))
            main { visible = false }; await("hidden by IME") { find(title) == null }
            main { visible = true }; await("header restored") { find(title) != null }
            assertNull(find(notice))
            main { panel = 4 }; delay(200); assertNull("Validation defaults compact while active", find(notice))
            click(title); assertNotNull(find(notice))
            main { state = state.copy(active = false) }; delay(200)
            assertNotNull("Completion must not override explicit expansion", find(notice))
            main { panel = 2 }; delay(200); assertNull(find(notice))
            main { panel = 4 }; await("validation choice retained") { find(notice) != null }
            main { panel = 0 }; delay(200); assertNull("Process choice remains independent", find(notice))
            main { state = state.copy(turn = 2, active = true) }
            await("new turn resets to active default") { find(notice) != null }
            main { state = state.copy(active = false) }
            await("untouched default folds on completion") { find(notice) == null }
            click(title)
            main { state = state.copy(conversationId = UUID.randomUUID().toString()) }
            await("new conversation does not inherit expansion") { find(notice) == null }
            click(title)
            main { state = state.copy(projectId = "fixture-b") }
            await("new project does not inherit expansion") { find(notice) == null }
        }
    }

    @Test(timeout = 180000) fun completedPlanLeavesRoomForActualValidationAndSurvivesActivityRecreation() = runBlocking {
        windows()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("idle host") { !host.state.value.loading && !host.state.value.busy && !host.ai.state.value.active && !host.ai.state.value.permissionsLoading }
            val previous = host.state.value.selectedId
            val previousAi = host.ai.state.value.projectId
            var owned: String? = null
            try {
                val name = "plan-ui-${UUID.randomUUID()}"
                main { host.create(name) }
                await("own project created") { !host.state.value.busy && host.state.value.project?.name == name }
                val id = host.state.value.selectedId!!; owned = id
                val conversation = UUID.randomUUID().toString()
                withContext(Dispatchers.IO) {
                    File(host.projects.directory(id), "main.py").writeText("print('PLAN_UI_FIXTURE')\n")
                    AiHistoryStore(context).save(id, AiState(projectId = id, conversationId = conversation, turn = 1,
                        phase = AiPhase.Completed, plan = steps, contextChars = 29169, round = 14, toolCount = 20))
                }
                main { host.ai.select(id); host.projectTasks.select(id) }
                await("fixture loaded") { !host.ai.state.value.permissionsLoading && host.ai.state.value.conversationId == conversation && !host.projectTasks.state.value.loading }
                for (kind in listOf("build", "test")) {
                    val command = ProjectCommand(kind, "python3 -c 'print(\"PLAN_UI_${kind.uppercase()}\")'", timeoutSeconds = 30)
                    main { host.projectTasks.save(command) }
                    await("command saved") { !host.projectTasks.state.value.saving }
                    val result = host.projectTasks.observed(id, command.shell(), 30000, {}, conversation, 1, "user")
                    assertEquals(0, result.exitCode)
                }
                click(context.getString(R.string.nav_ai))
                val title = context.getString(R.string.ai_current_plan)
                val notice = context.getString(R.string.ai_plan_reported)
                await("completed header") { find(title) != null }
                assertNull(find(notice))
                click(title); assertNotNull(find(notice))
                click(context.getString(R.string.project_validation))
                await("source check complete") { !host.projectTasks.state.value.sourceChecking && host.projectTasks.state.value.sourceCheckedAt > 0 }
                assertNull("Validation has its own compact default", find(notice))
                val compactTop = bounds(context.getString(R.string.project_validation_commands)).top
                screenshot("plan-validation-compact")
                // Normal font must show both kind labels without scrolling; large font may require scrolling.
                if (context.resources.configuration.fontScale <= 1.05f) {
                    assertNotNull("Build is visible", find(context.getString(R.string.project_build)))
                    assertNotNull("Test is visible", find(context.getString(R.string.project_test)))
                }
                click(title); await("expanded notice") { find(notice) != null }
                val expandedTop = bounds(context.getString(R.string.project_validation_commands)).top
                assertTrue("Compact plan must reclaim actual viewport space", expandedTop - compactTop > 100)
                instrumentation.sendStatus(0, Bundle().apply { putString("planLayoutProof", "compactCommandsTop=$compactTop; expandedCommandsTop=$expandedTop; reclaimedPixels=${expandedTop - compactTop}; actualBuildTestExit=0") })
                screenshot("plan-validation-expanded")
                click(context.getString(R.string.ai_requests)); assertNull(find(notice))
                click(context.getString(R.string.project_validation)); assertNotNull(find(notice))
                scenario.recreate()
                await("actual app restored validation choice") { find(notice) != null && find(context.getString(R.string.project_validation_commands)) != null }
                screenshot("plan-validation-recreated")
                click(context.getString(R.string.close))
                assertNotNull("Conversation plan expansion survives secondary pages", find(notice))
                assertTrue(editable()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("actual IME hides header") { find(title) == null }
                screenshot("plan-validation-ime")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await("actual IME dismissal restores choice") { find(title) != null && find(notice) != null }
                click(title); assertNull(find(notice))
                click(context.getString(R.string.project_validation)); assertNotNull("Validation choice retained", find(notice))
                click(context.getString(R.string.close)); assertNull("Conversation choice remains independent", find(notice))
                click(context.getString(R.string.ai_new_conversation))
                await("new conversation clears old plan") { host.ai.state.value.conversationId != conversation && find(title) == null }
            } finally {
                owned?.let { id -> main { host.removeProject(id) }; await("remove own project") { !host.state.value.busy && host.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { host.select(id) }; await("restore project") { !host.state.value.busy } }
                main { host.ai.select(previousAi) }
                await("restore AI selection") { !host.ai.state.value.permissionsLoading }
            }
        }
    }
}
