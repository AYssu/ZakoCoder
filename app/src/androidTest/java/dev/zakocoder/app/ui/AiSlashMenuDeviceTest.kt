package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual AiPage commands, isolated storage and a local model fixture; no external services. */
@SdkSuppress(minSdkVersion = 30)
class AiSlashMenuDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

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
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(value: String) = nodes { all -> all.any { text(it) == value } }
    private fun has(label: Int) = has(context.getString(label))
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { label }; Thread.sleep(40) }
    }
    private fun clickText(value: String) = nodes { all ->
        var node: SemanticsNode? = all.last { text(it) == value || value in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun click(label: Int) = clickText(context.getString(label))
    private fun input(value: String) {
        instrumentation.waitForIdleSync()
        nodes { all ->
            val editor = all.first { it.config.contains(SemanticsProperties.EditableText) && it.config.contains(SemanticsActions.SetText) }
            assertTrue(editor.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
        // SetText publishes mutable state before Compose installs the new send callback.
        // Wait for the actual editor semantics and the frame, then dispatch the action.
        await("composer receives the complete command") {
            nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == value } }
        }
        instrumentation.waitForIdleSync()
    }
    private fun windowRoots(): Set<android.view.View> {
        val result = AtomicReference<Set<android.view.View>>()
        instrumentation.runOnMainSync {
            result.set(android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }.toSet())
        }
        return result.get()
    }
    private fun dismiss(label: Int) {
        val beforeWindows = windowRoots()
        val visibleClose = nodes { all -> all.any { context.getString(R.string.close) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() || text(it) == context.getString(R.string.close) } }
        if (visibleClose) click(R.string.close) else instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("command sheet finishes exiting and removes its window") {
            !has(label) && beforeWindows.any { it !in windowRoots() }
        }
        instrumentation.waitForIdleSync()
    }

    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        CompositionLocalProvider(LocalDensity provides Density(density, 1f), LocalConfiguration provides configuration,
            LocalWorkbenchDark provides dark) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }
    private fun capture(dark: Boolean, state: String) {
        instrumentation.waitForIdleSync()
        // A visible semantics tree may precede the final sheet/drawing animation frame.
        Thread.sleep(600)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val target = File(context.cacheDir, "ui-review/slash-commands/${if (dark) "night" else "day"}-$state.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun helpScrollNode(all: List<SemanticsNode>): SemanticsNode {
        var node: SemanticsNode? = all.first { text(it) == context.getString(R.string.ai_slash_help_title) }
        while (node != null && !node.config.contains(SemanticsProperties.VerticalScrollAxisRange)) node = node.parent
        return requireNotNull(node)
    }

    private fun helpTail(scenario: ActivityScenario<MainActivity>, dark: Boolean) {
        fun range() = nodes { all -> helpScrollNode(all).config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() to it.maxValue() } }
        fun atBottom() = range().let { it.first >= it.second - 2f }
        repeat(4) {
            if (!atBottom()) {
                val bounds = nodes { all -> helpScrollNode(all).boundsInWindow }
                val x = bounds.center.x
                val startY = bounds.top + bounds.height * .8f
                val endY = bounds.top + bounds.height * .2f
                val started = SystemClock.uptimeMillis()
                fun event(action: Int, y: Float) = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0).also {
                    try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
                }
                event(MotionEvent.ACTION_DOWN, startY)
                repeat(20) { step ->
                    Thread.sleep(16)
                    event(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (step + 1) / 20f)
                }
                event(MotionEvent.ACTION_UP, endY)
                Thread.sleep(250)
            }
        }
        await("native help-panel drag reaches the last command") { atBottom() }
        // Capture before assertions so a navigation-bar overlap has concrete visual evidence.
        capture(dark, "help-tail")
        var safeBottom = 0f
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val navigation = requireNotNull(decor.rootWindowInsets).getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            safeBottom = (decor.height - navigation).toFloat()
        }
        nodes { all ->
            val viewport = helpScrollNode(all).boundsInWindow
            val token = all.last { text(it) == "/stop" }
            val explanation = all.last { text(it) == context.getString(R.string.ai_slash_stop) }
            println("Help tail layout: token=${token.boundsInWindow}, explanation=${explanation.boundsInWindow}, viewport=$viewport, safeBottom=$safeBottom")
            listOf(token, explanation).forEach { node ->
                val bounds = node.boundsInWindow
                assertTrue("The last help command and explanation must remain fully visible", bounds.height >= node.size.height * .98f)
                assertTrue("The last command must stay within the scroll viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom + 1f)
                assertTrue("The last command must remain above the system navigation area", bounds.bottom <= safeBottom)
            }
        }
    }

    @Test(timeout = 150000) fun actualSlashCommandsOpenLocalPanelsAndNeverSendUnknownCommands() = commands(false)
    @Test(timeout = 150000) fun actualSlashCommandsOpenLocalPanelsAndNeverSendUnknownCommandsNight() = commands(true)

    @Test(timeout = 150000) fun doctorOpensWithoutWorkspaceOrProvider() = doctor(false)
    @Test(timeout = 150000) fun doctorOpensWithoutWorkspaceOrProviderNight() = doctor(true)

    private fun doctor(dark: Boolean) = runBlocking {
        val base = context
        val root = File(base.cacheDir, "slash-doctor-${UUID.randomUUID()}")
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${root.name}-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        try {
            AiDeviceTest.Fixture(isolated).use { fixture ->
                // Intentionally omit the configured fixture store: diagnostics must work without a provider.
                val wb = withContext(Dispatchers.Main) { Workbench(isolated,
                    aiTransport = fixture.transport, aiHistory = fixture.history) }
                workbench = wb
                await("empty diagnostic workbench initialized") {
                    !wb.state.value.loading && !wb.ai.models.state.value.loading && !wb.ai.models.state.value.saving &&
                        !wb.ai.state.value.restoring && !wb.ai.state.value.permissionsLoading && !wb.ai.state.value.permissionsSaving
                }
                assertNull(wb.state.value.project)
                assertFalse(wb.ai.state.value.configured)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent { Theme(dark) {
                        val state by wb.state.collectAsState()
                        AiPage(wb, state, onModels = {}, onProjects = {}, onTerminal = {})
                    } } }
                    await("workspace-free composer available for diagnostics") { has(R.string.ai_prompt) }
                    // Initial model/history observers may recompose the keyed composer after
                    // its placeholder first appears. Wait for the session and editor identity
                    // to settle before SetText; focus can be owned by an ancestor semantics node.
                    instrumentation.waitForIdleSync()
                    var previousIdentity: Triple<String?, String, Int>? = null
                    var unchangedSince = SystemClock.uptimeMillis()
                    await("workspace-free doctor composer identity settles") {
                        val state = wb.ai.state.value
                        val editorId = nodes { all -> all.firstOrNull {
                            it.config.contains(SemanticsProperties.EditableText) &&
                                it.config.contains(SemanticsActions.SetText) &&
                                !it.config.contains(SemanticsProperties.Disabled)
                        }?.id }
                        val identity = editorId?.let { Triple(state.projectId, state.conversationId, it) }
                        if (identity != previousIdentity) {
                            previousIdentity = identity
                            unchangedSince = SystemClock.uptimeMillis()
                        }
                        identity != null && !state.restoring && !state.permissionsLoading &&
                            !state.permissionsSaving && SystemClock.uptimeMillis() - unchangedSince >= 200
                    }
                    input("/doctor")
                    await("local doctor suggestion available") { has("/doctor") }
                    clickText("/doctor")
                    await("diagnostics open without selecting workspace or model") {
                        has(R.string.doctor_title) && has(R.string.doctor_export) && has(R.string.doctor_privacy)
                    }
                    assertNull(wb.ai.state.value.projectId)
                    assertFalse(wb.ai.state.value.configured)
                    assertFalse(wb.ai.state.value.active)
                    assertEquals("Doctor must never call a model", 0, fixture.server.requestCount)
                    capture(dark, "doctor")
                    dismiss(R.string.doctor_title)
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                withTimeout(15000) { while (workbench?.ai?.state?.value?.active == true) delay(20) }
                withContext(Dispatchers.IO) { root.deleteRecursively(); preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }

    @Test(timeout = 150000) fun skillCommandLoadsGlobalCatalogWithoutCreatingWorkspace() = runBlocking {
        val base = context
        val root = File(base.cacheDir, "slash-global-skills-${UUID.randomUUID()}")
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${root.name}-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        try {
            AiDeviceTest.Fixture(isolated).use { fixture ->
                val wb = withContext(Dispatchers.Main) { Workbench(isolated, aiTransport = fixture.transport,
                    aiConfig = fixture.config, aiHistory = fixture.history) }
                workbench = wb
                await("isolated empty workbench initialized") { !wb.state.value.loading && !wb.ai.models.state.value.loading }
                assertNull(wb.state.value.project)
                assertNull(wb.ai.state.value.projectId)
                // Do not create/select a workspace or warm the global catalog: the actual
                // slash action must load it after the input is cleared by command dispatch.
                assertFalse(wb.ai.globalSkills.value.skillsInitialized)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent { Theme(false) {
                        val state by wb.state.collectAsState()
                        AiPage(wb, state, onModels = {}, onProjects = {}, onTerminal = {})
                    } } }
                    await("workspace-free composer is available") { has(R.string.ai_prompt) }
                    input("/skill")
                    await("skill command is suggested without workspace selection") { has("/skill") }
                    clickText("/skill")
                    await("skill action remains active while global catalog loads") {
                        wb.ai.globalSkills.value.skillsInitialized && !wb.ai.globalSkills.value.skillsLoading &&
                            has(R.string.ai_slash_panel_skill_title)
                    }
                    val skill = wb.ai.globalSkills.value.skills.first { it.valid }
                    await("the actual panel displays a valid global skill") { has(skill.name) }
                    assertNull(wb.ai.state.value.projectId)
                    assertNull(wb.state.value.project)
                    assertTrue(wb.state.value.projects.isEmpty())
                    assertEquals(0, fixture.server.requestCount)
                    capture(false, "global-skills")
                    dismiss(R.string.ai_slash_panel_skill_title)
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                withTimeout(15000) { while (workbench?.ai?.state?.value?.active == true) delay(20) }
                withContext(Dispatchers.IO) { root.deleteRecursively(); preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }

    private fun commands(dark: Boolean) = runBlocking {
        val base = context
        val root = File(base.cacheDir, "slash-ui-${UUID.randomUUID()}")
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${root.name}-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        try {
            AiDeviceTest.Fixture(isolated).use { fixture ->
                val wb = withContext(Dispatchers.Main) { Workbench(isolated, aiTransport = fixture.transport,
                    aiConfig = fixture.config, aiHistory = fixture.history) }
                workbench = wb
                await("isolated workbench initialized") { !wb.state.value.loading }
                withContext(Dispatchers.Main) { wb.create("命令菜单验证") }
                await("isolated workspace created") { !wb.state.value.busy && wb.state.value.project != null }
                val project = requireNotNull(wb.state.value.project)
                withContext(Dispatchers.Main) { wb.ai.select(project.id) }
                await("workspace history and command configuration ready") {
                    wb.ai.state.value.projectId == project.id && !wb.ai.state.value.permissionsLoading &&
                        !wb.ai.state.value.restoring && wb.ai.state.value.configured && !wb.ai.models.state.value.loading &&
                        !wb.ai.state.value.skillsLoading && wb.ai.state.value.skills.any { it.valid }
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent { Theme(dark) {
                        val state by wb.state.collectAsState()
                        AiPage(wb, state, onModels = {}, onProjects = {}, onTerminal = {})
                    } } }
                    await("actual composer ready") { has(R.string.ai_prompt) }
                    input("/")
                    await("inline slash suggestions appear") { has("/help") && has("/new") }
                    capture(dark, "menu")
                    clickText("/help")
                    // A suggestion may execute immediately or fill the input for explicit send.
                    Thread.sleep(200)
                    if (!has(R.string.ai_slash_help_title)) click(R.string.ai_send)
                    await("help command opens its local panel") { has(R.string.ai_slash_help_title) }
                    assertEquals(0, fixture.server.requestCount)
                    capture(dark, "help")
                    helpTail(scenario, dark)
                    dismiss(R.string.ai_slash_help_title)

                    input("/context")
                    click(R.string.ai_send)
                    await("context command opens the current conversation panel") { has(R.string.ai_slash_context_summary) }
                    capture(dark, "context")
                    dismiss(R.string.ai_slash_context_summary)

                    input("/model")
                    click(R.string.ai_send)
                    await("model command reuses model picker") { has(R.string.ai_choose_model) }
                    assertTrue(has("fixture-model"))
                    dismiss(R.string.ai_choose_model)

                    input("/skill:")
                    val skill = wb.ai.state.value.skills.first { it.valid }
                    await("skill names are discoverable in the composer") { has("/skill:${skill.name}") }
                    capture(dark, "skills")
                    input("/foo")
                    click(R.string.ai_send)
                    await("unknown commands show an actionable error") { has(R.string.ai_slash_unknown) }
                    assertEquals("Local slash commands never call a model", 0, fixture.server.requestCount)
                    assertTrue(wb.ai.state.value.timeline.isEmpty())

                    val previousConversation = wb.ai.state.value.conversationId
                    input("/new")
                    click(R.string.ai_send)
                    await("new command creates a conversation in the same workspace") {
                        wb.ai.state.value.conversationId != previousConversation && !wb.ai.state.value.permissionsLoading
                    }
                    assertEquals(project.id, wb.ai.state.value.projectId)
                    assertTrue(wb.ai.state.value.timeline.isEmpty())
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                withTimeout(15000) { while (workbench?.ai?.state?.value?.active == true) delay(20) }
                withContext(Dispatchers.IO) { root.deleteRecursively(); preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }
}
