package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiEntry
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.ai.AiHistoryStore
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.OnboardingProgress
import dev.zakocoder.app.settings.OnboardingRepository
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated real workspace/history storage; no model requests, installs or shell commands. */
@SdkSuppress(minSdkVersion = 30)
class WorkspaceNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all)
    }

    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun shown(text: String) = nodes { all -> all.any { label(it) == text && it.boundsInWindow.height > 0f } }
    private fun click(text: String) = nodes { all ->
        val target = all.last { label(it) == text || text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        val action = generateSequence(target) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue("Action is available: $text", action())
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { message }; Thread.sleep(40) }
        instrumentation.waitForIdleSync()
    }
    private fun capture(dark: Boolean, name: String) {
        Thread.sleep(450)
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val directory = File(base.cacheDir, "ui-review/workspaces").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 180000) fun isolatedWorkspaceConversationsAndCreationModes() = runBlocking {
        warmDeviceActivity()
        val token = "workspace-navigation-${UUID.randomUUID()}"
        val own = File(base.cacheDir, token).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val original = base
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences(
                "$token-$name".also { synchronized(names) { names += it } }, mode)
        }
        var workbench: Workbench? = null
        try {
            val history = AiHistoryStore(isolated)
            val projects = withContext(Dispatchers.IO) {
                isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
                OnboardingRepository(isolated).save(OnboardingProgress(completed = true, environmentConfirmed = false))
                val repository = ProjectRepository(isolated, File(isolated.filesDir, "workspaces").apply { mkdirs() })
                val list = listOf(repository.create("Workspace Alpha"), repository.create("Workspace Beta"))
                repository.save(list)
                repository.select(list.first().id)
                list
            }
            val conversationIds = projects.map { UUID.randomUUID().toString() }
            val titles = listOf("Only Alpha conversation", "Only Beta conversation")
            withContext(Dispatchers.IO) {
                projects.forEachIndexed { index, project -> history.save(project.id,
                    AiState(projectId = project.id, conversationId = conversationIds[index],
                        timeline = listOf(AiEntry(UUID.randomUUID().toString(), titles[index], role = AiRole.User)))) }
                projects.forEachIndexed { index, project ->
                    assertEquals(titles[index], AiHistoryStore(isolated).load(project.id).conversations.single().title)
                }
            }
            val host = main { Workbench(isolated, aiHistory = history) }.also { workbench = it }
            await("Isolated workbench did not load") { !host.state.value.loading }
            var selectedProject by mutableStateOf(projects.first())
            var addMode by mutableStateOf(false)
            var addRevision by mutableIntStateOf(0)
            val navigations = AtomicInteger()
            val imports = AtomicInteger()
            val creates = AtomicInteger()
            val clones = AtomicInteger()
            val bindings = AtomicInteger()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    main { selectedProject = projects.first(); addMode = false; assertTrue(host.openWorkspace(projects.first().id)) }
                    await("Alpha workspace transaction did not finish") {
                        host.state.value.selectedId == projects.first().id && !host.state.value.busy &&
                            host.ai.state.value.let { it.projectId == projects.first().id && !it.permissionsLoading && !it.historyError }
                    }
                    scenario.onActivity { activity -> activity.setContent {
                        val state by host.state.collectAsState()
                        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                            WorkbenchMiuixTheme(dark) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                    if (addMode) key(addRevision) {
                                        AddProjectSheet(enabled = true, onDismiss = { addMode = false },
                                            onImport = { imports.incrementAndGet() }, onCreate = { creates.incrementAndGet() },
                                            onClone = { clones.incrementAndGet() }, onBind = { bindings.incrementAndGet() })
                                    } else WorkspaceConversationsPage(host, state, selectedProject,
                                        onBack = {}, onFiles = {}, onTerminal = {}, onAi = { navigations.incrementAndGet() })
                                }
                            }
                        }
                    } }
                    await("Alpha conversation missing") { shown(titles[0]) }
                    assertFalse("Beta history must not appear in Alpha", shown(titles[1]))
                    capture(dark, "alpha-conversations")
                    val beforeNavigation = navigations.get()
                    click(titles[0])
                    await("Existing conversation did not navigate") { navigations.get() == beforeNavigation + 1 }
                    assertEquals(conversationIds[0], host.ai.state.value.conversationId)
                    main { selectedProject = projects[1]; assertTrue(host.openWorkspace(projects[1].id)) }
                    await("Beta workspace transaction did not finish") {
                        host.state.value.selectedId == projects[1].id && !host.state.value.busy &&
                            host.ai.state.value.let { it.projectId == projects[1].id && !it.permissionsLoading && !it.historyError }
                    }
                    await("Beta conversation missing") { shown(titles[1]) }
                    assertFalse("Alpha history must not appear in Beta", shown(titles[0]))
                    capture(dark, "beta-conversations")
                    val beforeBetaNavigation = navigations.get()
                    click(titles[1])
                    await("Specified Beta conversation did not navigate") { navigations.get() == beforeBetaNavigation + 1 }
                    assertEquals(conversationIds[1], host.ai.state.value.conversationId)
                    val beforeNew = navigations.get()
                    click(base.getString(R.string.workspace_threads_new))
                    await("New conversation did not navigate") { navigations.get() == beforeNew + 1 }
                    val createdId = host.ai.state.value.conversationId
                    assertNotEquals(conversationIds[1], createdId)
                    val reloaded = withContext(Dispatchers.IO) { AiHistoryStore(isolated).load(projects[1].id, createdId) }
                    assertEquals(createdId, reloaded.state?.conversationId)
                    assertTrue(reloaded.conversations.any { it.id == createdId })
                    assertFalse(reloaded.conversations.any { it.id == conversationIds[0] })

                    main { addRevision++; addMode = true }
                    await("Workspace mode picker missing") { shown(base.getString(R.string.workspace_flow_local)) }
                    assertTrue(shown(base.getString(R.string.workspace_flow_local)))
                    assertFalse(shown(base.getString(R.string.mobile_import_source)))
                    assertFalse(shown(base.getString(R.string.mobile_create_empty)))
                    assertFalse(shown(base.getString(R.string.git_clone)))
                    capture(dark, "add-workspace-modes")
                    click(base.getString(R.string.workspace_flow_work))
                    await("Work actions missing") { shown(base.getString(R.string.mobile_import_source)) && shown(base.getString(R.string.mobile_create_empty)) && shown(base.getString(R.string.git_clone)) }
                    assertTrue(shown(base.getString(R.string.mobile_create_empty)))
                    assertTrue(shown(base.getString(R.string.git_clone)))
                    capture(dark, "work-actions")
                    val beforeBind = bindings.get()
                    main { addRevision++ }
                    await("Workspace mode picker did not reset") { shown(base.getString(R.string.workspace_flow_local)) }
                    click(base.getString(R.string.workspace_flow_local))
                    assertEquals(beforeBind + 1, bindings.get())
                    assertEquals(0, imports.get())
                    assertEquals(0, creates.get())
                    assertEquals(0, clones.get())
                    assertFalse("Local action must not reveal Work actions", shown(base.getString(R.string.mobile_import_source)))
                    main { addMode = false }
                }
            }
        } finally {
            withContext(NonCancellable) {
                main { workbench?.stopAll() }
                delay(300)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(own)
                    synchronized(names) { names.toList() }.forEach { original.deleteSharedPreferences(it) }
                }
            }
        }
    }
}
