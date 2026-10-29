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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
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

    @Test(timeout = 300000) fun extensionNavigationPreservesDraftAndReferenceAndSkillsBelongToSelectedConversation() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val launcher = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Adaptive launcher loads on device", launcher is android.graphics.drawable.AdaptiveIconDrawable)
        if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull("Themed icon supplied", (launcher as android.graphics.drawable.AdaptiveIconDrawable).monochrome)
        val fixture = withContext(Dispatchers.IO) {
            AiDeviceTest.Fixture(context).also {
                it.config.saveProfile(AiModelDraft(null, "备用连接", it.server.url("/v1").toString(), "fixture-secondary", ""))
            }
        }
        val privateContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${fixture.token}-$name", mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        try {
            main { owned = Workbench(privateContext, fixture.transport, fixture.config, AiPermissionStore(privateContext), fixture.history) }
            val workbench = requireNotNull(owned)
            await("private workbench") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            val projectName = "扩展验收项目"
            main { workbench.create(projectName) }
            await("owned project") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
            val firstTitle = "检查首页入口"; val secondTitle = "检查构建脚本"
            withContext(Dispatchers.IO) {
                File(workbench.projects.directory(project.id), "entry.txt").writeText("OWNED_EXTENSION_REFERENCE\n")
                fixture.history.save(project.id, AiState(projectId = project.id, conversationId = first, turn = 1, skillsInitialized = true, enabledSkills = emptyList(), timeline = listOf(AiEntry("first-user", firstTitle, role = AiRole.User))))
                fixture.history.save(project.id, AiState(projectId = project.id, conversationId = second, turn = 1, skillsInitialized = true, enabledSkills = AiBuiltinSkills.defaults, timeline = listOf(AiEntry("second-user", secondTitle, role = AiRole.User))))
                fixture.history.select(project.id, first)
            }
            main { workbench.ai.select(project.id) }
            await("first conversation") { workbench.ai.state.value.conversationId == first && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading }
            // Some MIUI devices block instrumentation cold starts while allowing shell foreground starts.
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun imeVisible(): Boolean {
                    var visible = false
                    scenario.onActivity { activity ->
                        visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    }
                    return visible
                }
                for (dark in listOf(false, true)) {
                    main { workbench.ai.conversation(first) }
                    await("first ready") { workbench.ai.state.value.conversationId == first && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading }
                    val suffix = if (dark) "dark" else "light"
                    scenario.onActivity { activity -> activity.setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.width(360.dp).fillMaxHeight()) { ZakoCoderApp(workbench) } }
                        }
                    } }
                    instrumentation.waitForIdleSync()
                    click(context.getString(R.string.nav_ai))
                    val draft = "保留未发送需求 $suffix"
                    main { assertTrue(workbench.ai.addReference(AiReference("entry.txt", "OWNED_EXTENSION_REFERENCE"))) }
                    field(draft)
                    val inputBounds = Rect().also { requireNotNull(edit()).getBoundsInScreen(it) }
                    val modelBounds = Rect().also { control(context.getString(R.string.ai_switch_model)).getBoundsInScreen(it) }
                    assertTrue("Model control is below composer", modelBounds.top >= inputBounds.bottom)
                    assertTrue("Model touch target", modelBounds.height() >= 48 * context.resources.displayMetrics.density - 1)
                    click(context.getString(R.string.ai_switch_model))
                    screenshot("model-picker-$suffix-360-font130")
                    val targetModel = if (workbench.ai.models.state.value.catalog.active?.model == "fixture-secondary") "legacy" else
                        workbench.ai.models.state.value.catalog.profiles.single { it.model == "fixture-secondary" }.id
                    val targetName = workbench.ai.models.state.value.catalog.profiles.single { it.id == targetModel }.name
                    click(targetName)
                    await("model switched without sending") { workbench.ai.models.state.value.catalog.activeId == targetModel && !workbench.ai.models.state.value.saving && find(draft) != null }
                    assertEquals("entry.txt", workbench.ai.state.value.references.single().path)
                    click(context.getString(R.string.ai_switch_model)); click(context.getString(R.string.ai_models_add))
                    await("direct add model") { edit(context.getString(R.string.ai_models_name)) != null }
                    screenshot("model-add-$suffix-360-font130")
                    click(context.getString(R.string.ai_models_back)); click(context.getString(R.string.ai_models_back))
                    await("draft after model editor") { find(draft) != null }
                    assertEquals(2, workbench.ai.models.state.value.catalog.profiles.size)
                    click(context.getString(R.string.ai_permissions))
                    screenshot("permissions-$suffix-360-font130")
                    click(context.getString(R.string.close))
                    await("draft after permissions") { find(draft) != null }
                    click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_extensions))
                    await("identity shown") { find(projectName) != null && find(firstTitle) != null }
                    assertTrue("Skill selection starts empty or retains only the tested choice", workbench.ai.state.value.enabledSkills.isEmpty() || workbench.ai.state.value.enabledSkills == listOf("builtin:frontend-design"))
                    field("frontend-design", context.getString(R.string.ai_skills_search))
                    val toggle = context.getString(R.string.ai_skill_toggle, "frontend-design")
                    await("specific skill") { find(toggle) != null }
                    if ("builtin:frontend-design" !in workbench.ai.state.value.enabledSkills) click(toggle)
                    await("skill saved") { workbench.ai.state.value.enabledSkills == listOf("builtin:frontend-design") && !workbench.ai.state.value.permissionsSaving }
                    screenshot("skill-$suffix-360-font130")
                    click(firstTitle); click(secondTitle)
                    await("second selection") { workbench.ai.state.value.conversationId == second && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading }
                    assertEquals(AiBuiltinSkills.defaults, workbench.ai.state.value.enabledSkills)
                    assertTrue("References must not cross conversations", workbench.ai.state.value.references.isEmpty())
                    click(secondTitle); click(firstTitle)
                    await("first selection restored") { workbench.ai.state.value.conversationId == first && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading }
                    assertEquals(listOf("builtin:frontend-design"), workbench.ai.state.value.enabledSkills)
                    click(context.getString(R.string.ai_extensions_back))
                    await("draft restored") { find(draft) != null }
                    assertEquals("entry.txt", workbench.ai.state.value.references.single().path)
                    for (detail in listOf(R.string.ai_requests, R.string.project_validation)) {
                        click(context.getString(R.string.ai_more)); click(context.getString(detail)); click(context.getString(R.string.close))
                        await("draft after detail") { find(draft) != null }
                        assertEquals("entry.txt", workbench.ai.state.value.references.single().path)
                    }
                    screenshot("conversation-$suffix-360-font130")
                    click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_extensions)); click(context.getString(R.string.mcp_title))
                    await("MCP empty") { find(context.getString(R.string.mcp_empty)) != null }
                    val add = Rect().also { control(context.getString(R.string.mcp_design_add_server)).getBoundsInScreen(it) }
                    assertTrue("Add touch target", add.height() >= 48 * context.resources.displayMetrics.density - 1)
                    screenshot("mcp-empty-$suffix-360-font130")
                    click(context.getString(R.string.mcp_design_add_server))
                    field("未保存的服务", context.getString(R.string.mcp_name))
                    screenshot("mcp-editor-$suffix-360-font130")
                    if (!dark) {
                        assertTrue(edit(context.getString(R.string.mcp_name))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        await("MCP editor keyboard") { imeVisible() }
                        screenshot("mcp-editor-light-ime-360-font130")
                        repeat(10) {
                            if (clickable(context.getString(R.string.mcp_save)) == null) {
                                assertTrue("Form remains scrollable with keyboard", scrollFormForward())
                                delay(180)
                            }
                        }
                        assertNotNull("Save remains reachable with keyboard", clickable(context.getString(R.string.mcp_save)))
                        screenshot("mcp-editor-light-ime-save-360-font130")
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        await("MCP editor keyboard dismissed") { !imeVisible() }
                    }
                    click(context.getString(R.string.close))
                    assertTrue(workbench.mcp.state.value.servers.isEmpty())
                    click(context.getString(R.string.mcp_design_add_server))
                    field("本地工具服务", context.getString(R.string.mcp_name))
                    field("/usr/bin/python3", context.getString(R.string.mcp_command))
                    click(context.getString(R.string.mcp_design_advanced))
                    repeat(8) { if (edit(context.getString(R.string.mcp_args)) == null) { scrollFormForward(); delay(100) } }
                    field("[", context.getString(R.string.mcp_args))
                    click(context.getString(R.string.mcp_save))
                    await("invalid JSON feedback visible") { find(context.getString(R.string.mcp_invalid_fields)) != null }
                    val errorBounds = Rect().also { requireNotNull(find(context.getString(R.string.mcp_invalid_fields))).getBoundsInScreen(it) }
                    val saveBounds = Rect().also { control(context.getString(R.string.mcp_save)).getBoundsInScreen(it) }
                    assertTrue("Invalid JSON feedback remains above fixed Save", errorBounds.height() > 0 && errorBounds.bottom <= saveBounds.top)
                    assertTrue("Invalid JSON never saves a service", workbench.mcp.state.value.servers.isEmpty())
                    screenshot("mcp-invalid-json-$suffix-360-font130")
                    repeat(8) { if (edit(context.getString(R.string.mcp_args)) == null) { scrollFormForward(); delay(100) } }
                    field("[\"-u\"]", context.getString(R.string.mcp_args))
                    repeat(8) { if (edit(context.getString(R.string.mcp_env)) == null) { scrollFormForward(); delay(100) } }
                    field("{\"MODE\":\"fixture\"}", context.getString(R.string.mcp_env))
                    assertTrue(requireNotNull(edit(context.getString(R.string.mcp_env))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    delay(500)
                    assertNotNull("Fixed Save reachable with final field focused", clickable(context.getString(R.string.mcp_save)))
                    screenshot("mcp-advanced-ime-$suffix-360-font130")
                    click(context.getString(R.string.mcp_save))
                    await("configured without connecting") { workbench.mcp.state.value.servers.size == 1 && !workbench.mcp.state.value.busy }
                    val saved = workbench.mcp.state.value.servers.single()
                    assertFalse(saved.enabled); assertEquals(listOf("-u"), saved.args); assertEquals(mapOf("MODE" to "fixture"), saved.env)
                    if (imeVisible()) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    delay(200)
                    screenshot("mcp-list-$suffix-360-font130")
                    click(context.getString(R.string.mcp_edit))
                    for (protocol in listOf(R.string.mcp_transport_http, R.string.mcp_transport_sse, R.string.mcp_transport_stdio)) {
                        click(context.getString(R.string.mcp_design_protocol)); click(context.getString(protocol))
                    }
                    click(context.getString(R.string.mcp_save))
                    await("protocol switches retain local fields") { !workbench.mcp.state.value.busy && find(context.getString(R.string.mcp_design_add_server)) != null }
                    assertEquals(saved.args, workbench.mcp.state.value.servers.single().args)
                    assertEquals(saved.env, workbench.mcp.state.value.servers.single().env)
                    click(context.getString(R.string.mcp_delete)); click(context.getString(R.string.mcp_delete))
                    await("deleted owned fixture") { workbench.mcp.state.value.servers.isEmpty() && !workbench.mcp.state.value.busy }
                    click(context.getString(R.string.ai_extensions_back))
                    for (page in listOf(R.string.nav_workspace, R.string.nav_environment, R.string.nav_terminal, R.string.nav_settings)) {
                        click(context.getString(page))
                        screenshot("page-${context.resources.getResourceEntryName(page)}-$suffix-360-font130")
                    }
                    click(context.getString(R.string.nav_workspace)); click(context.getString(R.string.browse_files)); click("entry.txt")
                    await("owned editor") { workbench.editor.state.value.document != null }
                    screenshot("editor-$suffix-360-font130")
                    click(context.getString(R.string.editor_search))
                    field("OWNED", context.getString(R.string.editor_find))
                    assertTrue(requireNotNull(edit(context.getString(R.string.editor_find))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("editor search keyboard") { imeVisible() }
                    withContext(Dispatchers.IO) { File(workbench.projects.directory(project.id), "entry.txt").appendText("DISK_EDIT\n") }
                    main { workbench.editor.checkDisk() }
                    await("editor detects changed disk") { workbench.editor.state.value.diskState == dev.zakocoder.app.editor.EditorDiskState.Changed }
                    screenshot("editor-search-disk-ime-$suffix-360-font130")
                    var codeHeight = 0
                    scenario.onActivity { activity ->
                        fun walk(view: android.view.View) {
                            if (view is io.github.rosemoe.sora.widget.CodeEditor) {
                                codeHeight = view.height
                                val scheme = view.colorScheme
                                val foreground = scheme.getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL)
                                val background = scheme.getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.MATCHED_TEXT_BACKGROUND)
                                assertTrue("Search match remains readable in $suffix theme", androidx.core.graphics.ColorUtils.calculateContrast(foreground, background) >= 4.5)
                            }
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                        }
                        walk(activity.window.decorView)
                    }
                    assertTrue("Code viewport keeps at least two lines with search, disk warning and IME", codeHeight >= 48 * context.resources.displayMetrics.density)
                    click(context.getString(R.string.editor_replace))
                    await("compact replacement remains available") { edit(context.getString(R.string.editor_replacement)) != null && find(context.getString(R.string.editor_replace_all)) != null }
                    click(context.getString(R.string.close))
                    assertTrue(requireNotNull(edit(context.getString(R.string.editor_find))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("compact disk details") { imeVisible() && find(context.getString(R.string.editor_disk_details)) != null }
                    click(context.getString(R.string.editor_disk_details))
                    await("compact disk details retain review and reload") { find(context.getString(R.string.editor_disk_compare)) != null && find(context.getString(R.string.editor_disk_load)) != null }
                    click(context.getString(R.string.close))
                    if (imeVisible()) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    click(context.getString(R.string.editor_close_search))
                    main { workbench.editor.close() }
                    await("editor closed") { workbench.editor.state.value.projectId == null }
                    withContext(Dispatchers.IO) { File(workbench.projects.directory(project.id), "entry.txt").writeText("OWNED_EXTENSION_REFERENCE\n") }
                    click(context.getString(R.string.project_actions)); click(context.getString(R.string.project_commands))
                    screenshot("commands-$suffix-360-font130")
                    click(context.getString(R.string.close)); click(context.getString(R.string.back_to_library))
                    click(context.getString(R.string.nav_settings)); click(context.getString(R.string.ai_extensions))
                    await("settings extension entry") { find(context.getString(R.string.extensions_context)) != null }
                    click(context.getString(R.string.ai_extensions_back))
                    assertEquals(0, fixture.server.requestCount)
                    assertFalse(workbench.ai.state.value.active); assertFalse(workbench.state.value.installed)
                    assertTrue(workbench.state.value.sessions.isEmpty()); assertTrue(workbench.mcp.state.value.servers.isEmpty())
                }
            }
            withContext(Dispatchers.IO) { assertEquals(listOf("builtin:frontend-design"), fixture.history.load(project.id, first).state!!.enabledSkills) }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll() }
            withContext(NonCancellable + Dispatchers.IO) {
                listOf("projects", "development-settings").forEach { context.deleteSharedPreferences("${fixture.token}-$it") }
                fixture.close()
            }
        }
    }
}
