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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
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
class ConversationDrawerDeviceTest {
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
    private fun click(text: String, first: Boolean = false) = nodes { all ->
        val candidates = all.filter { label(it) == text || text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        val target = if (first) candidates.first() else candidates.last()
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
        val directory = File(base.cacheDir, "ui-review/conversation-drawer").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 180000) fun groupedConversationsAndFixedUtilities() = runBlocking {
        warmDeviceActivity()
        val token = "conversation-drawer-${UUID.randomUUID()}"
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
            val ids = projects.map { UUID.randomUUID().toString() }
            val titles = listOf("Alpha drawer conversation", "Beta drawer conversation")
            withContext(Dispatchers.IO) {
                projects.forEachIndexed { index, project -> history.save(project.id,
                    AiState(projectId = project.id, conversationId = ids[index], timeline = listOf(
                        AiEntry(UUID.randomUUID().toString(), titles[index], role = AiRole.User)))) }
            }
            val host = main { Workbench(isolated, aiHistory = history) }.also { workbench = it }
            await("Isolated workbench did not load") { !host.state.value.loading }
            main { assertTrue(host.openWorkspace(projects[0].id)) }
            await("Alpha workspace did not load") {
                !host.state.value.busy && host.state.value.selectedId == projects[0].id &&
                    host.ai.state.value.let { it.projectId == projects[0].id && !it.permissionsLoading && !it.historyError }
            }
            val opened = AtomicReference<Pair<String, String?>?>()
            val manage = AtomicInteger()
            val utilities = AtomicReference<AppDestination?>()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    opened.set(null)
                    utilities.set(null)
                    scenario.onActivity { activity -> activity.setContent {
                        val state by host.state.collectAsState()
                        key(dark) {
                        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                            WorkbenchMiuixTheme(dark) {
                                val drawer = rememberDrawerState(DrawerValue.Open)
                                AppNavigation(drawerState = drawer, selected = AppDestination.Ai, version = "Test",
                                    gesturesEnabled = true, onClose = {}, onSelect = { utilities.set(it) },
                                    conversations = {
                                        ConversationDrawer(host, state, visible = true, busy = false, error = false,
                                            onManage = { manage.incrementAndGet() },
                                            onOpen = { project, conversation -> opened.set(project to conversation) })
                                    }) { Surface(Modifier.fillMaxSize()) {} }
                            }
                        }
                        }
                    } }
                    await("Current workspace conversation missing") { shown(titles[0]) }
                    assertFalse("Other workspace starts collapsed", shown(titles[1]))
                    val alphaProject = host.ai.state.value.projectId
                    val alphaConversation = host.ai.state.value.conversationId
                    click(projects[1].name)
                    await("Expanded Beta conversation missing") { shown(titles[1]) }
                    assertEquals("Expanding a group must not switch selected workspace", projects[0].id, host.state.value.selectedId)
                    assertEquals(alphaProject, host.ai.state.value.projectId)
                    assertEquals(alphaConversation, host.ai.state.value.conversationId)
                    click(titles[1])
                    assertEquals(projects[1].id to ids[1], opened.get())
                    click(titles[0])
                    assertEquals(projects[0].id to ids[0], opened.get())
                    click(base.getString(R.string.drawer_new_conversation), first = true)
                    assertEquals(projects[0].id to null, opened.get())
                    val previousManage = manage.get()
                    click(base.getString(R.string.drawer_manage_workspaces))
                    assertEquals(previousManage + 1, manage.get())
                    val destinations = listOf(AppDestination.Environment, AppDestination.Terminal, AppDestination.Settings)
                    destinations.forEach { destination ->
                        val text = base.getString(destination.label)
                        assertTrue("Fixed utility is visible: $text", shown(text))
                        click(text)
                        assertEquals(destination, utilities.get())
                    }
                    nodes { all ->
                        val header = all.first { label(it) == base.getString(R.string.drawer_conversations) }.boundsInWindow
                        destinations.forEach { destination ->
                            val footer = all.first { label(it) == base.getString(destination.label) }.boundsInWindow
                            assertTrue("Utility belongs below conversation region", footer.top > header.bottom)
                        }
                    }
                    capture(dark, "expanded-workspaces")
                    click(projects[0].name)
                    await("Alpha group did not collapse") { !shown(titles[0]) }
                    assertTrue("Beta stays expanded independently", shown(titles[1]))
                    destinations.forEach { assertTrue(shown(base.getString(it.label))) }
                    assertEquals("Navigation callbacks do not mutate workspace until host handles them", projects[0].id, host.state.value.selectedId)
                    assertEquals(alphaConversation, host.ai.state.value.conversationId)
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
