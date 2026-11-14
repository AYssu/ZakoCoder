package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual root navigation with isolated files, preferences, history and model transport. */
@SdkSuppress(minSdkVersion = 30)
class AppNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                all += node
                if (!node.config.isClearingSemantics) node.children.forEach(::collect)
            }
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

    private fun matches(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

    private fun clickable(node: SemanticsNode): SemanticsNode? =
        generateSequence(node) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun drawerItem(node: SemanticsNode): SemanticsNode? =
        generateSequence(node) { it.parent }.firstOrNull {
            it.config.getOrNull(SemanticsProperties.Selected) != null
        }

    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        return bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.center.x > 0 && bounds.center.x < context.resources.displayMetrics.widthPixels
    }

    private fun await(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(label)
            Thread.sleep(30)
        }
    }

    private fun has(label: String) = nodes { all -> all.any { matches(it, label) && visible(it) } }

    private fun click(label: String, destinationOnly: Boolean = false) {
        fun eligible(node: SemanticsNode) = matches(node, label) && visible(node) &&
            (!destinationOnly || drawerItem(node) != null)
        await("Visible action: $label") { nodes { all -> all.any(::eligible) } }
        nodes { all ->
            val target = requireNotNull(clickable(all.asReversed().first(::eligible)))
            assertNull("Enabled: $label", target.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue("Click: $label", target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }

    private fun drawerClosed() = !has(context.getString(R.string.conversation_search_title))
    private fun ensureActivityResumed(scenario: ActivityScenario<MainActivity>) {
        assertEquals("Back keeps the application Activity", Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { assertFalse(it.isFinishing) }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/navigation/${if (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "night" else "day"}-$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 240_000)
    fun isolatedDrawerOpeningBackSixShortcutsAndCancelledPageBackPreserveDraft(): Unit = runBlocking {
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
        try {
            warmDeviceActivity()
            withContext(Dispatchers.Main) {
                owned = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history)
            }
            val workbench = requireNotNull(owned)
            await("isolated workbench initialized") { !workbench.state.value.loading &&
                !workbench.ai.models.state.value.loading && workbench.capabilityPackages.state.value.loaded }
            withContext(Dispatchers.Main) { workbench.completeOnboarding(); workbench.create("导航验收工作区") }
            await("fixture onboarding completed and workspace created") {
                !workbench.state.value.onboarding.required && !workbench.state.value.busy &&
                    workbench.state.value.projects.size == 1
            }
            val fixtureProject = workbench.state.value.projects.single()
            withContext(Dispatchers.Main) { workbench.ai.select(fixtureProject.id) }
            await("fixture AI workspace selected before root mounts") {
                workbench.ai.state.value.projectId == fixtureProject.id &&
                    !workbench.ai.state.value.restoring && !workbench.ai.state.value.permissionsLoading
            }
            val projectId = workbench.ai.state.value.projectId
            val conversationId = workbench.ai.state.value.conversationId
            val menu = context.getString(R.string.app_navigation_open)
            val search = context.getString(R.string.conversation_search_title)
            val draft = "NAVIGATION_DRAFT_MUST_SURVIVE"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent { ZakoCoderApp(workbench) }
                }
                await("assistant root ready") { has(menu) && nodes { all -> all.any {
                    it.config.getOrNull(SemanticsActions.SetText) != null && visible(it)
                } } }
                nodes { all ->
                    val input = all.last { it.config.getOrNull(SemanticsActions.SetText) != null && visible(it) }
                    assertTrue(input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true)
                }
                fun assertDraft() {
                    await("assistant draft survives navigation") { nodes { all -> all.any {
                        visible(it) && it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
                    } } }
                    assertEquals(projectId, workbench.ai.state.value.projectId)
                    assertEquals(conversationId, workbench.ai.state.value.conversationId)
                    assertFalse(workbench.ai.state.value.active)
                }
                fun keyBack() { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }

                // Back while the real drawer is opening must only close navigation.
                click(menu)
                await("drawer header is visible") { has(search) }
                keyBack()
                await("Back closes the opening drawer") { drawerClosed() }
                ensureActivityResumed(scenario)
                assertDraft()

                val shortcuts = listOf(R.string.nav_settings, R.string.nav_environment, R.string.nav_terminal,
                    R.string.ai_skills, R.string.mcp_title, R.string.capability_packages_title)
                for (destination in shortcuts) {
                    click(menu)
                    await("drawer visible") { has(search) }
                    await("all six shortcuts finish sliding into view") {
                        shortcuts.all { resource -> nodes { all -> all.any {
                            matches(it, context.getString(resource)) && visible(it) && drawerItem(it) != null
                        } } }
                    }
                    val label = context.getString(destination)
                    val captureKey = when (destination) {
                        R.string.ai_skills -> "skill"
                        R.string.mcp_title -> "mcp"
                        R.string.capability_packages_title -> "capabilities"
                        else -> "utility"
                    }
                    click(label, destinationOnly = true)
                    await("destination covers retained drawer: $label") { drawerClosed() }
                    ensureActivityResumed(scenario)
                    // This exercises the active page handler, including Skill, MCP and capabilities.
                    // These injected AndroidX events prove state/cancellation, not OEM gesture support.
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(
                        BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT)) }
                    delay(80)
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(
                        BackEventCompat(0f, 600f, .35f, BackEventCompat.EDGE_LEFT)) }
                    delay(100)
                    if (destination in listOf(R.string.ai_skills, R.string.mcp_title, R.string.capability_packages_title))
                        capture("$captureKey-back-progress")
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    delay(650)
                    ensureActivityResumed(scenario)
                    if (destination in listOf(R.string.ai_skills, R.string.mcp_title, R.string.capability_packages_title))
                        capture("$captureKey-back-cancelled")
                    await("cancel retains the foreground destination: $label") { has(label) && drawerClosed() }
                    keyBack()
                    await("page Back restores the originating drawer: $label") { has(search) }
                    keyBack()
                    await("restored drawer closes without changing its source") { drawerClosed() }
                    delay(400)
                    await("page Back preserves assistant underneath") { has(menu) && nodes { all -> all.any {
                        visible(it) && it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
                    } } }
                    assertDraft()
                }

                // Utilities launched over the workspace library must return there, not to chat.
                click(menu)
                await("drawer available before managing workspaces") { has(search) }
                click(context.getString(R.string.drawer_manage_workspaces))
                await("workspace library visible") { has(context.getString(R.string.workspace_flow_list)) && drawerClosed() }
                for (destination in listOf(R.string.nav_settings, R.string.mcp_title)) {
                    click(menu)
                    await("workspace-origin drawer visible") { has(search) }
                    click(context.getString(destination), destinationOnly = true)
                    await("workspace utility opened") { drawerClosed() }
                    delay(400)
                    keyBack()
                    await("utility returns to workspace-origin drawer") { has(search) }
                    keyBack()
                    await("workspace origin survives utility Back") {
                        drawerClosed() && has(context.getString(R.string.workspace_flow_list))
                    }
                }
                click(menu)
                fun windowCount(): Int {
                    val result = java.util.concurrent.atomic.AtomicInteger()
                    instrumentation.runOnMainSync { result.set(android.view.inspector.WindowInspector.getGlobalWindowViews().size) }
                    return result.get()
                }
                val windowsBeforeSearch = windowCount()
                click(search)
                await("real conversation search sheet opens") { has(context.getString(R.string.conversation_search_hint)) }
                keyBack()
                await("search sheet closes without navigation") { !has(context.getString(R.string.conversation_search_hint)) }
                await("search dialog finishes dismissal before drawer Back") { windowCount() <= windowsBeforeSearch }
                await("Activity regains focus after search dismissal") {
                    val focused = java.util.concurrent.atomic.AtomicBoolean()
                    scenario.onActivity { focused.set(it.hasWindowFocus()) }
                    focused.get()
                }
                instrumentation.waitForIdleSync()
                // The six destination checks above cover native Back. This final cleanup
                // verifies page/draft restoration without racing the departing dialog's
                // asynchronous WindowManager focus transfer or an input method callback.
                if (!drawerClosed()) scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await("search return restores assistant") { drawerClosed() }
                assertTrue("search returns to original workspace page", has(context.getString(R.string.workspace_flow_list)))
                assertEquals("Navigation never sends a model request", 0, fixture.server.requestCount)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll(); owned?.browser?.close() }
            withContext(NonCancellable + Dispatchers.IO) {
                fixture.close()
                synchronized(preferences) { preferences.forEach(context::deleteSharedPreferences) }
            }
        }
    }
}
