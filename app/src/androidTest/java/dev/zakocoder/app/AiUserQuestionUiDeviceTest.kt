package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.AiPage
import dev.zakocoder.app.ui.AiQuestionCard
import dev.zakocoder.app.ui.LocalWorkbenchDark
import dev.zakocoder.app.ui.WorkbenchColors
import dev.zakocoder.app.ui.WorkbenchDarkColors
import dev.zakocoder.app.ui.WorkbenchTypography
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/** Component interaction evidence only; controller persistence is exercised by its own device tests. */
class AiUserQuestionUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(descendants(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::descendants)
    }
    private fun find(label: String) = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString()?.lines()?.contains(label) == true ||
            it.contentDescription?.toString()?.lines()?.contains(label) == true)
    }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(15_000) { while (!predicate()) delay(80) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun clickable(label: String): AccessibilityNodeInfo? {
        var node = find(label) ?: return null
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.takeIf { it.isEnabled && it.isClickable }
    }
    private suspend fun click(label: String) {
        await(label) {
            if (clickable(label) != null) true else {
                nodes().firstOrNull { it.isVisibleToUser && it.isScrollable && !it.isEditable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
        val node = requireNotNull(clickable(label))
        assertTrue("Enabled action: $label", node.isEnabled)
        assertTrue("Click: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(150)
    }
    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/ai-user-question/$name.png").apply { parentFile!!.mkdirs() }
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
    @Test(timeout = 120_000) fun dayOptionsCustomReceiptAndCancellation() = runBlocking { exercise(false) }
    @Test(timeout = 120_000) fun nightOptionsCustomReceiptAndCancellation() = runBlocking { exercise(true) }
    @Test(timeout = 120_000) fun dayRealAiPagePausesAndContinuesWithOption() = runBlocking { exercisePage(false) }
    @Test(timeout = 120_000) fun nightRealAiPagePausesAndContinuesWithCustomAnswer() = runBlocking { exercisePage(true) }

    private suspend fun exercisePage(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck"))
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${fixture.token}-$name", mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        val prefix = if (dark) "night" else "day"
        try {
            withContext(Dispatchers.Main) {
                owned = Workbench(isolated, aiTransport = fixture.transport, aiConfig = fixture.config,
                    aiHistory = fixture.history, aiPermissions = AiPermissionStore(isolated))
            }
            val workbench = requireNotNull(owned)
            await("Isolated workbench loaded") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            withContext(Dispatchers.Main) { workbench.create("移动端提问验收") }
            await("Isolated project created") { !workbench.state.value.busy && workbench.state.value.project != null }
            withContext(Dispatchers.Main) { workbench.ai.select(workbench.state.value.selectedId) }
            await("Isolated project selected") {
                workbench.ai.state.value.projectId == workbench.state.value.selectedId && workbench.ai.state.value.configured &&
                    !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading
            }
            val callId = "ui-clarification"
            fixture.enqueue(calls = listOf(AiCall(callId, "ask_user", JSONObject()
                .put("question", "首页采用哪种布局？").put("options", JSONArray(listOf("项目列表", "卡片网格")))
                .put("reason", "开始实现前确认布局").toString())))
            fixture.enqueue("布局已确认，我会按你的选择继续原任务。")
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            val pageState by workbench.state.collectAsStateWithLifecycle()
                            Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
                                AiPage(workbench, pageState, onModels = {}, onProjects = {}, onTerminal = {})
                            }
                        }
                    }
                } }
                withContext(Dispatchers.Main) { assertTrue(workbench.ai.send("请先询问我首页的布局，再继续实现。")) }
                await("Actual controller pauses for answer") {
                    workbench.ai.state.value.phase == AiPhase.AwaitingAnswer && !workbench.ai.state.value.active
                }
                val originalTurn = workbench.ai.state.value.turn
                await("Question in actual conversation") { find(context.getString(R.string.ai_question_waiting)) != null }
                var composer = requireNotNull(find(context.getString(R.string.ai_question_composer_hint)))
                while (composer.isEnabled && composer.className?.toString() != "android.widget.EditText" && composer.parent != null) composer = composer.parent
                assertFalse("Ordinary composer is disabled while waiting", composer.isEnabled)
                var stop = requireNotNull(find(context.getString(R.string.ai_stop)))
                while (!stop.isClickable && stop.parent != null) stop = stop.parent
                assertTrue("Stop remains enabled while waiting", stop.isEnabled && stop.isClickable)
                assertEquals("No model polling while awaiting user", 1, fixture.server.requestCount)
                screenshot("$prefix-page-waiting")
                val expected = if (dark) "使用列表，增加按名称搜索。" else "项目列表"
                if (dark) {
                    await("Question custom answer editor") { nodes().any { it.isVisibleToUser && it.isEditable && it.isEnabled } }
                    val editor = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled })
                    assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected)
                    }))
                    delay(150)
                } else click(expected)
                click(context.getString(R.string.ai_question_submit))
                await("Actual resumed model request completes") {
                    !workbench.ai.state.value.active && workbench.ai.state.value.phase == AiPhase.Completed
                }
                val completed = workbench.ai.state.value
                assertEquals(originalTurn, completed.turn)
                assertEquals(expected, completed.questions.single().answer)
                assertNull(completed.questionContinuation)
                assertEquals("", completed.error)
                assertEquals(1, completed.timeline.count { it.role == AiRole.User })
                assertEquals(2, fixture.server.requestCount)
                val request = withContext(Dispatchers.IO) {
                    requireNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
                    requireNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
                }
                val messages = JSONObject(request).getJSONArray("messages")
                val result = (0 until messages.length()).map(messages::getJSONObject)
                    .single { it.optString("tool_call_id") == callId }
                assertEquals("tool", result.getString("role"))
                assertTrue("Actual TLS continuation includes user's answer", result.getString("content").contains(expected))
                await("Answered receipt in actual conversation") { find(context.getString(R.string.ai_question_answered)) != null }
                await("Smoothed response finishes rendering") { find("布局已确认，我会按你的选择继续原任务。") != null }
                screenshot("$prefix-page-answered")
            }
        } finally {
            withContext(Dispatchers.Main) { owned?.stopAll() }
            withContext(Dispatchers.IO) { fixture.close() }
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private suspend fun exercise(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck"))
        val automation = instrumentation.uiAutomation
        val previousInfo = automation.serviceInfo
        val previousFlags = previousInfo.flags
        automation.serviceInfo = previousInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val question = mutableStateOf(AiUserQuestion(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "首页使用哪一种布局？确定后我会继续实现，并保留现有项目文件。", listOf("项目列表，显示最近编辑", "卡片网格，突出项目预览"), 1))
        val captured = AtomicReference<String>()
        val resumed = AtomicInteger()
        val prefix = if (dark) "night" else "day"
        try {
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(LocalDensity.current.density, if (dark) 1.3f else 1f)) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    AiQuestionCard(question.value, true, onAnswer = {
                                        captured.set(it)
                                        question.value = question.value.copy(answer = it)
                                    }, onResume = if (question.value.answer == null) null else ({ resumed.incrementAndGet(); Unit }))
                                }
                            }
                        }
                    }
                } }
                await("Question is displayed") { find(context.getString(R.string.ai_question_waiting)) != null }
                screenshot("$prefix-waiting")
                click(question.value.options.first())
                screenshot("$prefix-option-selected")
                click(context.getString(R.string.ai_question_submit))
                await("Selected answer returned") { captured.get() == question.value.options.first() }
                await("Read-only answer receipt") { find(context.getString(R.string.ai_question_answered)) != null }
                assertFalse(nodes().any { it.isVisibleToUser && it.isEditable })
                screenshot("$prefix-answered")
                click(context.getString(R.string.ai_question_resume))
                assertEquals(1, resumed.get())

                scenario.onActivity { question.value = question.value.copy(id = UUID.randomUUID().toString(), answer = null) }
                await("New question editor") { nodes().any { it.isVisibleToUser && it.isEditable } }
                click(question.value.options.last())
                val custom = "保留列表，增加按名称搜索，不需要卡片网格。"
                val editor = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.isEditable })
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "  $custom  ")
                }))
                delay(180)
                screenshot("$prefix-custom")
                click(context.getString(R.string.ai_question_submit))
                await("Custom answer supersedes choice and is trimmed") { captured.get() == custom }
                scenario.onActivity { question.value = question.value.copy(cancelled = true) }
                await("Cancelled receipt") { find(context.getString(R.string.ai_question_cancelled)) != null }
                assertNull(find(context.getString(R.string.ai_question_resume)))
                assertFalse(nodes().any { it.isVisibleToUser && it.isEditable })
                screenshot("$prefix-cancelled-receipt")

                scenario.onActivity { question.value = question.value.copy(id = UUID.randomUUID().toString(), answer = null) }
                await("Cancelled question") { find(context.getString(R.string.ai_question_cancelled_note)) != null }
                assertNull(find(context.getString(R.string.ai_question_submit)))
                assertNull(find(context.getString(R.string.ai_question_waiting_note)))
                assertFalse(nodes().any { it.isVisibleToUser && it.isEditable })
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
