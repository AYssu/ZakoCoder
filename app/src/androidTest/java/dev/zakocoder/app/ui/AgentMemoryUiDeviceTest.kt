package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.memory.MemoryController
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the actual page and store with synthetic records, without a workspace or model. */
@SdkSuppress(minSdkVersion = 30)
class AgentMemoryUiDeviceTest {
    private val ui = UpgradeUiHarness()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun dayMemoryCrudSearchCoreSwitchSuggestionAndCancel() = review(false)
    @Test(timeout = 180000) fun nightMemoryCrudSearchCoreSwitchSuggestionAndCancel() = review(true)

    private fun review(dark: Boolean) {
        val root = File(context.cacheDir, "memory-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = MemoryController(isolated, scope)
        val theme = if (dark) "night" else "day"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ui.configureSystemBars(activity, dark)
                    activity.setContent {
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize().systemBarsPadding()) { AgentMemoryPage(controller) {} }
                        }
                    }
                }
                ui.await("Empty memory library") { ui.has(R.string.agent_memory_empty) && controller.state.value.loaded }
                ui.capture("memory/$theme-empty")
                ui.click(R.string.agent_memory_add)
                ui.setField(R.string.agent_memory_name, "取消草稿")
                ui.click(R.string.close)
                ui.await("Dirty draft asks before discarding") { ui.has(R.string.agent_memory_discard_title) }
                ui.click(R.string.cancel)
                ui.await("Keep editing preserves draft") {
                    !ui.has(R.string.agent_memory_discard_title) &&
                        ui.fieldValue(R.string.agent_memory_name) == "取消草稿"
                }
                ui.click(R.string.close)
                ui.click(R.string.agent_memory_discard)
                ui.await("Canceled draft does not save") { ui.has(R.string.agent_memory_empty) }
                assertTrue(controller.state.value.entries.isEmpty())

                ui.click(R.string.agent_memory_add)
                ui.setField(R.string.agent_memory_name, "界面测试记忆")
                ui.setField(R.string.agent_memory_content, "修改前先阅读现有实现，再做最小变更。")
                ui.reveal(context.getString(R.string.agent_memory_core))
                ui.toggleOnlyControl()
                ui.click(R.string.agent_memory_save)
                ui.await("Created core memory") { controller.state.value.entries.singleOrNull()?.core == true && ui.has("界面测试记忆") }
                val id = controller.state.value.entries.single().id
                assertTrue(runBlocking { controller.contextFor("任意任务") }.core.any { it.id == id })
                ui.capture("memory/$theme-saved-core")

                ui.setField(R.string.agent_memory_search, "不存在的检索词")
                ui.await("Search empty state") { ui.has(R.string.agent_memory_no_results) && !controller.state.value.searching }
                assertEquals(1, controller.state.value.entries.size)
                ui.setField(R.string.agent_memory_search, "")
                ui.await("Cleared query restores the saved memory") { ui.has("界面测试记忆") && !controller.state.value.searching }
                ui.click("界面测试记忆")
                ui.await("Full content opens in a dedicated detail page") { ui.has(R.string.agent_memory_details) }
                ui.capture("memory/$theme-detail")
                ui.click(R.string.agent_memory_edit)
                ui.setField(R.string.agent_memory_name, "已更新的开发偏好")
                ui.setField(R.string.agent_memory_content, "修改后运行对应的定向测试，并记录验证结果。")
                ui.capture("memory/$theme-editor")
                ui.click(R.string.agent_memory_save)
                ui.await("Edited record saved") { controller.state.value.entries.singleOrNull()?.title == "已更新的开发偏好" && ui.has("已更新的开发偏好") }
                assertEquals(id, controller.state.value.entries.single().id)
                val enable = context.getString(R.string.agent_memory_enable_label, "已更新的开发偏好")
                // Exercise real pointer dispatch: the nested switch must consume the card tap.
                ui.tap(enable)
                ui.await("Memory is disabled without opening details") {
                    controller.state.value.entries.singleOrNull()?.enabled == false && !ui.has(R.string.agent_memory_details)
                }
                assertTrue(runBlocking { controller.contextFor("开发偏好") }.entries.isEmpty())
                ui.tap(enable)
                ui.await("Memory is enabled again without opening details") {
                    controller.state.value.entries.singleOrNull()?.enabled == true && !ui.has(R.string.agent_memory_details)
                }

                ui.click("已更新的开发偏好")
                ui.click(R.string.agent_memory_delete)
                ui.await("Delete confirmation") { ui.has(R.string.agent_memory_delete_title) }
                ui.capture("memory/$theme-delete-confirmation")
                ui.click(R.string.cancel)
                ui.await("Cancel keeps the original record") { !ui.has(R.string.agent_memory_delete_title) }
                assertEquals(1, controller.state.value.entries.size)
                ui.click(R.string.agent_memory_delete)
                ui.await("Delete confirmation reopened") { ui.has(R.string.agent_memory_delete_title) }
                ui.click(R.string.agent_memory_delete)
                ui.await("Confirmed deletion persists") { controller.state.value.entries.isEmpty() && ui.has(R.string.agent_memory_empty) }

                runBlocking { controller.propose("错误处理偏好", "失败时先保留完整错误日志，再定位原因。", "测试任务建议").join() }
                ui.await("Suggestion remains pending") { controller.state.value.proposals.size == 1 }
                ui.click(context.getString(R.string.agent_memory_filter_pending, 1))
                ui.await("Pending suggestions are separated from saved memories") { ui.has("错误处理偏好") && !ui.has(R.string.agent_memory_confirm_save) }
                assertTrue(runBlocking { controller.contextFor("错误处理") }.entries.isEmpty())
                ui.capture("memory/$theme-suggestion")
                ui.click("错误处理偏好")
                ui.await("Suggestion detail requires explicit confirmation") { ui.has(R.string.agent_memory_confirm_save) }
                ui.click(R.string.agent_memory_confirm_save)
                ui.await("Explicit confirmation activates the suggestion") { controller.state.value.proposals.isEmpty() && controller.state.value.entries.singleOrNull()?.enabled == true }
                assertEquals("错误处理偏好", runBlocking { controller.contextFor("错误处理") }.relevant.single().title)
                ui.click(context.getString(R.string.agent_memory_filter_all, 1))
                ui.await("Confirmed memory is listed with saved records") { ui.has("错误处理偏好") }
                ui.capture("memory/$theme-confirmed")
                runBlocking {
                    controller.add("代码修改偏好", "修改前阅读现有实现，完成后运行受影响的验证。", core = true).join()
                    controller.add("运行服务的习惯", "服务使用明确端口，启动后确认可访问再打开预览。").join()
                    controller.propose("提交偏好", "提交前核对文件变更和实际验证结果。", "conversation:synthetic-review").join()
                }
                ui.await("Memory overview includes saved and pending counts") {
                    ui.has(context.getString(R.string.agent_memory_filter_all, 3)) &&
                        ui.has(context.getString(R.string.agent_memory_filter_pending, 1))
                }
                ui.capture("memory/$theme-overview")
                ui.click(context.getString(R.string.agent_memory_filter_core, 1))
                ui.await("Priority filter only shows core records") { ui.has("代码修改偏好") && !ui.has("运行服务的习惯") }
                ui.capture("memory/$theme-priority")
                ui.click(context.getString(R.string.agent_memory_filter_pending, 1))
                ui.setField(R.string.agent_memory_search, "没有的建议")
                ui.await("Search also filters pending suggestions") { ui.has(R.string.agent_memory_no_results) && !controller.state.value.searching }
                ui.click(R.string.agent_memory_clear_search)
                ui.await("Clear search restores pending suggestions") { ui.has("提交偏好") }
                assertTrue(runBlocking { controller.contextFor("提交偏好") }.entries.none { it.pending })
                val reloaded = MemoryController(isolated, scope)
                runBlocking { reloaded.load().join() }
                assertEquals(controller.state.value.entries, reloaded.state.value.entries)
            }
        } finally { runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }; root.deleteRecursively() }
    }
}

/** Semantics actions are preferred; native switch pointer fallback is bounded to the actual labelled control. */
internal class UpgradeUiHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Suppress("DEPRECATION")
    fun configureSystemBars(activity: android.app.Activity, dark: Boolean) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val color = if (dark) android.graphics.Color.BLACK else android.graphics.Color.rgb(245, 246, 248)
        activity.window.statusBarColor = color
        activity.window.navigationBarColor = color
        activity.window.decorView.setBackgroundColor(color)
        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    fun has(id: Int) = has(context.getString(id))
    fun has(label: String): Boolean = nodes { all -> all.any { matches(it, label) && visible(it) } }
    fun click(id: Int) = click(context.getString(id))
    fun click(label: String) {
        reveal(label)
        val position = AtomicReference<Pair<Float, Float>?>(null)
        nodes { all ->
            val matches = all.filter { matches(it, label) && visible(it) }
            val chosen = matches.asReversed().firstNotNullOfOrNull { match ->
                // Labels may sit on a wrapper while the actionable switch is its child.
                // Walking straight to ancestors would activate a containing card instead.
                fun actionWithin(node: SemanticsNode): SemanticsNode? {
                    if (!visible(node)) return null
                    if (node.config.getOrNull(SemanticsActions.OnClick) != null) return node
                    return node.children.firstNotNullOfOrNull(::actionWithin)
                }
                actionWithin(match) ?: run {
                    var node = match.parent
                    while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                    node
                }
            }
            if (chosen != null) assertTrue("Click $label", chosen.config[SemanticsActions.OnClick].action?.invoke() == true)
            else {
                val node = requireNotNull(matches.lastOrNull()) { "Missing clickable control: $label" }
                val center = node.boundsInWindow.center
                position.set(center.x to center.y)
            }
        }
        position.get()?.let(::pointer)
        SystemClock.sleep(180)
    }
    /** Real touch coverage for controls nested in independently clickable cards. */
    fun tap(label: String) {
        reveal(label)
        val point = nodes { all ->
            val target = all.lastOrNull { matches(it, label) && visible(it) }
                ?: error("Missing touch target: $label")
            target.boundsInWindow.center.let { it.x to it.y }
        }
        pointer(point)
        SystemClock.sleep(180)
    }
    fun toggleOnlyControl() {
        val point = AtomicReference<Pair<Float, Float>?>(null)
        nodes { all ->
            val switches = all.filter { visible(it) && (it.config.getOrNull(SemanticsProperties.Role) == Role.Switch ||
                it.config.getOrNull(SemanticsProperties.ToggleableState) != null) }
                .distinctBy { it.boundsInWindow }
            val node = switches.singleOrNull() ?: error("Expected one visible editor switch, got ${switches.size}")
            val action = node.config.getOrNull(SemanticsActions.OnClick)
            if (action != null) assertTrue(action.action?.invoke() == true)
            else node.boundsInWindow.center.let { point.set(it.x to it.y) }
        }
        point.get()?.let(::pointer)
        SystemClock.sleep(180)
    }
    fun setField(id: Int, value: String) {
        val label = context.getString(id)
        reveal(label)
        await("Editable field $label") { nodes { all -> all.any { editableFor(it, label) } } }
        nodes { all ->
            val field = all.last { editableFor(it, label) }
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
        SystemClock.sleep(100)
    }
    /** Editable text is a distinct semantics property from static Text in Miuix fields. */
    fun fieldValue(id: Int): String? {
        val label = context.getString(id)
        return nodes { all -> all.lastOrNull { editableFor(it, label) }
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text }
    }
    fun reveal(label: String) {
        if (has(label)) return
        repeat(2) { pass ->
            if (pass == 1) scrollTop()
            repeat(10) {
                if (has(label)) return
                val scrolled = nodes { all -> all.lastOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                    ?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, 450f) == true }
                if (!scrolled) SystemClock.sleep(100) else SystemClock.sleep(160)
            }
        }
        await("Visible control $label") { has(label) }
    }
    fun scrollTop() {
        nodes { all -> all.lastOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            ?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, -10000f) }
        SystemClock.sleep(180)
    }
    fun capture(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.cacheDir, "ui-review/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!ready()) { check(SystemClock.uptimeMillis() < deadline) { label }; SystemClock.sleep(40) }
    }
    private fun editableFor(node: SemanticsNode, label: String): Boolean {
        if (node.config.getOrNull(SemanticsActions.SetText) == null || !visible(node)) return false
        var parent: SemanticsNode? = node
        while (parent != null) {
            if (parent.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) return true
            parent = parent.parent
        }
        return false
    }
    private fun matches(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun visible(node: SemanticsNode) = node.boundsInWindow.let { it.width > 0 && it.height > 0 }
    private fun pointer(point: Pair<Float, Float>) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, point.first, point.second, 0)
        val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, point.first, point.second, 0)
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
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
}
