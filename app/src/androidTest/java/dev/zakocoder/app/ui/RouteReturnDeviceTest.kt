package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Route hand-off must start at neutral opacity and may not activate a covered settings row. */
@SdkSuppress(minSdkVersion = 30)
class RouteReturnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var lifecycleDescription: (() -> String)? = null
    private var failureMode = "unknown"

    private fun await(message: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (!check()) {
            if (SystemClock.uptimeMillis() >= until) {
                val failure = AssertionError(message)
                try {
                    screenshot("$failureMode-failure")
                    val labels = nodes { all -> all.filter(::visible).flatMap { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.distinct() }
                    val details = AtomicReference<String>()
                    instrumentation.runOnMainSync {
                        details.set(WindowInspector.getGlobalWindowViews().joinToString("\n") {
                            "${it.javaClass.name} shown=${it.isShown} focus=${it.hasWindowFocus()} attached=${it.isAttachedToWindow}"
                        })
                    }
                    File(context.cacheDir, "ui-review/route-return/$failureMode-failure.txt").apply {
                        parentFile!!.mkdirs()
                        writeText("$message\nlifecycle=${lifecycleDescription?.invoke()}\nwindows=${windows()}\n${details.get()}\nvisible-labels=\n${labels.joinToString("\n")}\n")
                    }
                } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
                throw failure
            }
            Thread.sleep(30)
        }
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val collected = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                collected += node
                // Retained preview pages intentionally clear accessibility while covered.
                // Unmerged traversal must honor that boundary instead of finding their inputs.
                if (!node.config.isClearingSemantics) node.children.forEach(::collect)
            }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(collected))
        }
        return result.get()
    }

    private fun visible(node: SemanticsNode): Boolean = node.boundsInWindow.let {
        it.width > 0 && it.height > 0 && it.right > 0 && it.bottom > 0 &&
            it.center.x > 0 && it.center.x < context.resources.displayMetrics.widthPixels
    }
    private fun matches(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    private fun has(label: String) = nodes { all -> all.any { visible(it) && matches(it, label) } }
    private fun click(label: String, drawer: Boolean = false) {
        fun eligible(node: SemanticsNode) = visible(node) && matches(node, label) && (!drawer ||
            generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Selected) != null })
        await("Visible action: $label") { nodes { all -> all.any(::eligible) } }
        nodes { all ->
            val action = generateSequence(all.last(::eligible)) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertNull(action.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(action.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/route-return/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
    private fun windows(): Int {
        val count = AtomicInteger()
        instrumentation.runOnMainSync { count.set(WindowInspector.getGlobalWindowViews().size) }
        return count.get()
    }
    private fun event(progress: Float) = BackEventCompat(0f, 600f, progress, BackEventCompat.EDGE_LEFT)
    private fun renderedFrame() {
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback { drawn.countDown() } }
        assertTrue("A UI frame was rendered", drawn.await(3, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    @Test(timeout = 120_000)
    fun sharedBackMotionStartsNewRouteNeutralInItsFirstComposition(): Unit {
        warmDeviceActivity()
        for (dark in listOf(false, true)) {
            val route = mutableStateOf("settings")
            val motion = AtomicReference<WorkbenchBackMotion>()
            val firstParentFrame = AtomicReference<Triple<Float, Float, Boolean>>()
            val parentClicks = AtomicInteger()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    UpgradeUiHarness().configureSystemBars(activity, dark)
                    activity.setContent {
                        WorkbenchMiuixTheme(dark) {
                            val shown = route.value
                            val back = rememberWorkbenchBackMotion(resetKey = shown) { route.value = "assistant" }
                            SideEffect {
                                motion.set(back)
                                if (shown == "assistant") firstParentFrame.compareAndSet(null,
                                    Triple(back.progress.value, back.exitProgress.value, back.exiting.value))
                            }
                            Box(Modifier.fillMaxSize().background(Color(0xFF21A88B))) {
                                Box(Modifier.fillMaxSize().then(back.modifier)
                                    .background(if (shown == "settings") Color(0xFF3764C8) else Color(0xFF21A88B))
                                    .clickable { parentClicks.incrementAndGet() }) { Text(shown) }
                            }
                        }
                    }
                }
                await("Shared motion ready") { motion.get() != null }
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f)) }
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.5f)) }
                Thread.sleep(80)
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await("New route composed") { firstParentFrame.get() != null }
                val first = requireNotNull(firstParentFrame.get())
                assertEquals("No inherited drag on first composition", 0f, first.first, 0f)
                assertEquals("No inherited invisible exit frame", 0f, first.second, 0f)
                assertFalse("New parent is interactive immediately", first.third)
                click("assistant")
                assertEquals(1, parentClicks.get())
                screenshot("${if (dark) "night" else "day"}-neutral-parent")
            }
        }
    }

    @Test(timeout = 240_000)
    fun settingsReturnThenAssistantBackExitsWithoutProxyAndKeepsDraft() = verifyRootBack(visitSettings = true)

    @Test(timeout = 240_000)
    fun launchThenImmediateBackExitsWithoutCreatingWorkspaceOrProxy() = verifyRootBack(visitSettings = false)

    private fun verifyRootBack(visitSettings: Boolean): Unit = runBlocking {
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
            await("Isolated workbench initialized") { !workbench.state.value.loading &&
                !workbench.ai.models.state.value.loading && workbench.capabilityPackages.state.value.loaded }
            withContext(Dispatchers.Main) { workbench.completeOnboarding(); workbench.create("返回路径验收") }
            await("Workspace created") { !workbench.state.value.onboarding.required &&
                !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            withContext(Dispatchers.Main) { workbench.create("另一个工作区") }
            await("Second workspace created") { !workbench.state.value.busy && workbench.state.value.projects.size == 2 }
            withContext(Dispatchers.Main) { workbench.ai.select(project.id) }
            await("Assistant ready") { workbench.ai.state.value.projectId == project.id &&
                !workbench.ai.state.value.restoring && !workbench.ai.state.value.permissionsLoading }
            val conversation = workbench.ai.state.value.conversationId
            val menu = context.getString(R.string.app_navigation_open)
            val search = context.getString(R.string.conversation_search_title)
            val proxy = context.getString(R.string.proxy_page_title)
            val draft = "RETURN_ROUTE_DRAFT_DO_NOT_SEND"
            for (dark in listOf(false, true)) {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    failureMode = if (dark) "night" else "day"
                    lifecycleDescription = { scenario.state.toString() }
                    val activityDecor = AtomicReference<android.view.View>()
                    scenario.onActivity { activity ->
                        activityDecor.set(activity.window.decorView)
                        UpgradeUiHarness().configureSystemBars(activity, dark)
                        activity.setContent {
                            val configuration = Configuration(LocalConfiguration.current).apply {
                                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                            }
                            CompositionLocalProvider(LocalConfiguration provides configuration) { ZakoCoderApp(workbench) }
                        }
                    }
                    fun draftVisible() = nodes { all -> all.any {
                        visible(it) && it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
                    } }
                    await("Assistant input visible") { has(menu) && nodes { all -> all.any {
                        visible(it) && it.config.getOrNull(SemanticsActions.SetText) != null
                    } } }
                    nodes { all ->
                        val field = all.last { visible(it) && it.config.getOrNull(SemanticsActions.SetText) != null }
                        assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true)
                    }
                    val initialWindows = windows()
                    repeat(if (visitSettings) 2 else 0) { attempt ->
                        click(menu)
                        await("Drawer opened") { has(search) }
                        click(context.getString(R.string.nav_settings), drawer = true)
                        await("Settings selected and drawer dismissed") { !has(search) && !draftVisible() }
                        renderedFrame()
                        screenshot("${if (dark) "night" else "day"}-settings-$attempt-covered")
                        if (attempt == 0) {
                            val proxyRow = context.getString(R.string.proxy_short)
                            repeat(16) {
                                if (!has(proxyRow)) {
                                    nodes { all -> all.lastOrNull { visible(it) &&
                                        it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                                        ?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 500f) }
                                    delay(100)
                                }
                            }
                            click(proxyRow)
                            fun proxyPageVisible() = has(context.getString(R.string.proxy_mode_direct)) &&
                                has(context.getString(R.string.proxy_mode_airport))
                            await("An intentional settings proxy click still opens its page") { proxyPageVisible() }
                            await("Proxy Dialog owns window focus before Back") {
                                val focused = java.util.concurrent.atomic.AtomicBoolean()
                                instrumentation.runOnMainSync {
                                    focused.set(WindowInspector.getGlobalWindowViews().any {
                                        it !== activityDecor.get() && it.hasWindowFocus()
                                    })
                                }
                                focused.get() && windows() > initialWindows
                            }
                            renderedFrame()
                            screenshot("${if (dark) "night" else "day"}-intentional-proxy")
                            instrumentation.waitForIdleSync()
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                            // The settings row and proxy page title deliberately share the
                            // same label; only the proxy-only controls identify this child.
                            await("Intentional proxy Back returns to settings") {
                                !proxyPageVisible() && windows() == initialWindows
                            }
                            await("Activity regains input after proxy closes") {
                                val focused = java.util.concurrent.atomic.AtomicBoolean()
                                scenario.onActivity { focused.set(it.hasWindowFocus()) }
                                focused.get()
                            }
                            renderedFrame()
                            assertFalse("Closing intentional proxy must retain settings", draftVisible())
                        }
                        // Cancellation must keep settings, and the eventual commit must not
                        // leak the covered network-proxy row into the following destination.
                        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f)) }
                        renderedFrame()
                        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.45f)) }
                        delay(80)
                        screenshot("${if (dark) "night" else "day"}-settings-$attempt-back-progress")
                        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                        delay(500)
                        assertFalse("Cancelled settings Back stays in settings", draftVisible())
                        screenshot("${if (dark) "night" else "day"}-settings-$attempt-cancelled")
                        scenario.onActivity { activity ->
                            activity.onBackPressedDispatcher.onBackPressed()
                            activity.onBackPressedDispatcher.onBackPressed()
                        }
                        await("Settings Back restores the drawer over its original page") { has(search) }
                        delay(400)
                        screenshot("${if (dark) "night" else "day"}-drawer-restored")
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                        await("Closing restored drawer preserves original assistant") { !has(search) && draftVisible() }
                        delay(400)
                        assertTrue("Parent remains after repeated Back", draftVisible())
                        assertFalse("Proxy was never opened", has(proxy))
                        assertEquals("No hidden proxy Dialog window", initialWindows, windows())
                        assertEquals(conversation, workbench.ai.state.value.conversationId)
                    }
                    screenshot("${if (dark) "night" else "day"}-assistant-return")
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f)) }
                    renderedFrame()
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.35f)) }
                    delay(80)
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    delay(500)
                    assertTrue("Cancelled assistant Back preserves message input", draftVisible())
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    await("Assistant root Back leaves the Activity instead of creating a workspace route") {
                        scenario.state != Lifecycle.State.RESUMED
                    }
                    assertEquals("No model requests during navigation", 0, fixture.server.requestCount)
                }
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
