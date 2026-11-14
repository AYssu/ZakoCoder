package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real root drawer → real history search → workspace/conversation navigation → visible match.
 * All histories, preferences and model transport belong to an isolated fixture; no user data is read.
 */
@SdkSuppress(minSdkVersion = 30)
class ConversationSearchNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 180_000) fun dayRootSearchOpensOtherWorkspaceToolAndReasoning(): Unit = runBlocking { exercise(false) }
    @Test(timeout = 180_000) fun nightRootSearchOpensOtherWorkspaceToolAndReasoning(): Unit = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
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
                synchronized(preferences) { preferences += key }
                return context.getSharedPreferences(key, mode)
            }
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        val theme = if (dark) "night" else "day"
        val toolText = "ROOT_SEARCH_TOOL_MATCH：真实历史文件的命中结果"
        val reasoningText = "公开思考的第一行。\nROOT_SEARCH_REASONING_MATCH：需要自动展开的完整内容"
        try {
            withContext(Dispatchers.IO) { shell("cmd uimode night ${if (dark) "yes" else "no"}"); warmDeviceActivity() }
            main { owned = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
            val workbench = requireNotNull(owned)
            await("isolated workbench initialized") { !workbench.state.value.loading &&
                !workbench.ai.models.state.value.loading && workbench.capabilityPackages.state.value.loaded }
            main { workbench.completeOnboarding(); workbench.create("全文检索工作区甲") }
            await("first workspace created") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 &&
                !workbench.state.value.onboarding.required }
            val first = workbench.state.value.projects.single()
            main { workbench.create("全文检索工作区乙") }
            await("second workspace created") { !workbench.state.value.busy && workbench.state.value.projects.size == 2 }
            val second = workbench.state.value.projects.single { it.id != first.id }

            fun tail() = List(24) { AiEntry("later-$it", "后续消息 $it：保留真实对话滚动距离。", role = AiRole.Assistant, turn = 1) }
            fun initial(title: String) = AiState(phase = AiPhase.Completed, turn = 1, timeline = listOf(
                AiEntry("initial-user", title, role = AiRole.User, turn = 1),
                AiEntry("initial-answer", "当前会话不是搜索目标。", role = AiRole.Assistant, turn = 1)))
            fun tool(id: String, output: String) = AiToolEvent(id, "call-$id", AiTool.ReadFile, "read_file", "{}",
                "检查文件", "$id.kt", status = AiToolStatus.Succeeded, output = output, turn = 1)
            val reasoning = AiState(phase = AiPhase.Completed, turn = 1, timeline = listOf(
                AiEntry("reasoning-user", "甲工作区的思考目标会话", role = AiRole.User, turn = 1),
                AiEntry("matched-reasoning", "已经检查。", role = AiRole.Assistant, reasoning = reasoningText, turn = 1)) + tail())
            val toolHistory = AiState(phase = AiPhase.Completed, turn = 1, timeline = listOf(
                AiEntry("tool-user", "乙工作区的工具目标会话", role = AiRole.User, turn = 1),
                AiEntry("neighbor-entry", toolId = "neighbor-event", turn = 1),
                AiEntry("matched-tool", toolId = "matched-event", turn = 1)) + tail(), events = listOf(
                tool("neighbor-event", "NEIGHBOR_DETAIL_MUST_STAY_CLOSED"), tool("matched-event", toolText)))
            val firstInitial = initial("甲工作区当前会话")
            val secondInitial = initial("乙工作区当前会话")
            withContext(Dispatchers.IO) {
                fixture.history.save(first.id, reasoning)
                fixture.history.save(first.id, firstInitial)
                fixture.history.save(second.id, toolHistory)
                fixture.history.save(second.id, secondInitial)
            }
            main { assertTrue(workbench.openWorkspace(first.id)) }
            await("first workspace current conversation restored") { !workbench.state.value.busy &&
                workbench.state.value.selectedId == first.id && workbench.ai.state.value.projectId == first.id &&
                workbench.ai.state.value.conversationId == firstInitial.conversationId && ready(workbench) }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { ZakoCoderApp(workbench) } }
                await("real assistant root mounted") { has(context.getString(R.string.app_navigation_open), description = true) }
                assertNull(workbench.ai.conversationFocus.value)

                suspend fun searchAndOpen(query: String) {
                    click(context.getString(R.string.app_navigation_open), description = true)
                    await("drawer search icon visible") { has(context.getString(R.string.conversation_search_title), description = true) }
                    capture("$theme-drawer-brand")
                    click(context.getString(R.string.conversation_search_title), description = true)
                    await("search sheet is visible") { has(context.getString(R.string.conversation_search_hint)) }
                    setSearch(query)
                    await("real on-disk history returns $query") { nodes { all -> all.any { text(it).contains(query) } } }
                    click(query, contains = true)
                }
                searchAndOpen("ROOT_SEARCH_TOOL_MATCH")
                await("search selects another workspace and exact historical conversation") {
                    workbench.state.value.selectedId == second.id && workbench.ai.state.value.projectId == second.id &&
                        workbench.ai.state.value.conversationId == toolHistory.conversationId && ready(workbench)
                }
                assertNotEquals(secondInitial.conversationId, workbench.ai.state.value.conversationId)
                await("matched tool group and output expand after real root navigation") { has(toolText) }
                await("tool focus consumed by destination conversation") { workbench.ai.conversationFocus.value == null }
                assertFalse("search sheet has closed", has(context.getString(R.string.conversation_search_hint)))
                assertFalse("unmatched neighboring tool remains folded", has("NEIGHBOR_DETAIL_MUST_STAY_CLOSED"))
                capture("$theme-tool")

                searchAndOpen("ROOT_SEARCH_REASONING_MATCH")
                await("search returns to first workspace and exact target conversation") {
                    workbench.state.value.selectedId == first.id && workbench.ai.state.value.projectId == first.id &&
                        workbench.ai.state.value.conversationId == reasoning.conversationId && ready(workbench)
                }
                assertNotEquals(firstInitial.conversationId, workbench.ai.state.value.conversationId)
                await("full reasoning expands rather than showing only its first-line preview") { has(reasoningText) }
                await("reasoning focus consumed by destination conversation") { workbench.ai.conversationFocus.value == null }
                assertFalse("tool details belong to the previous conversation", has(toolText))
                capture("$theme-reasoning")
                assertEquals("Search, switching and revealing history must not call a model", 0, fixture.server.requestCount)
                assertFalse(workbench.ai.state.value.active)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll(); owned?.browser?.close() }
            withContext(NonCancellable + Dispatchers.IO) {
                try { fixture.close(); synchronized(preferences) { preferences.forEach(context::deleteSharedPreferences) } }
                finally { shell("cmd uimode night $previousNight") }
            }
        }
    }

    private fun ready(workbench: Workbench) = workbench.ai.state.value.let {
        !it.restoring && !it.permissionsLoading && !it.skillsLoading && !it.active
    }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(30_000) { while (!predicate()) delay(50) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(label: String, description: Boolean = false) = nodes { all -> all.any {
        it.boundsInWindow.height > 0 && if (description) it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
        else text(it) == label
    } }
    private suspend fun click(label: String, description: Boolean = false, contains: Boolean = false) {
        fun target(all: List<SemanticsNode>): SemanticsNode? = all.asReversed().firstNotNullOfOrNull { candidate ->
            val match = if (description) candidate.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                else if (contains) text(candidate).contains(label) else text(candidate) == label
            if (!match || candidate.boundsInWindow.height <= 0) return@firstNotNullOfOrNull null
            var node: SemanticsNode? = candidate
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            node?.takeUnless { it.config.contains(SemanticsProperties.Disabled) }
        }
        await("clickable $label") { nodes { target(it) != null } }
        nodes { all -> assertTrue(label, requireNotNull(target(all)).config[SemanticsActions.OnClick].action?.invoke() == true) }
    }
    private suspend fun setSearch(value: String) {
        await("search input") { nodes { all -> all.any { it.config.getOrNull(SemanticsActions.SetText) != null } } }
        nodes { all ->
            val node = all.last { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
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
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private suspend fun capture(name: String) {
        delay(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/conversation-search-navigation/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } } finally { bitmap.recycle() }
    }
}
