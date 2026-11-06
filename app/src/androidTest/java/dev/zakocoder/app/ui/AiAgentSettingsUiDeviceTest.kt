package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.R
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual switch, state ownership, persisted choice and task lock in a 360dp/130% sheet. */
@SdkSuppress(minSdkVersion = 30)
class AiAgentSettingsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day360Font130() = render(false)
    @Test fun night360Font130() = render(true)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { label }; Thread.sleep(25) }
    }
    private fun switchNode(block: (SemanticsNode) -> Unit) = instrumentation.runOnMainSync {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
        fun visit(view: android.view.View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all.single { context.getString(R.string.ai_auto_delegate) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
    }
    private fun render(dark: Boolean) {
        AiDeviceTest.Fixture(context).use { f ->
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            val ai = AtomicReference<AiController>()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("No command") }, {}, {}, f.transport, f.config, history = f.history)
                    ai.set(controller); controller.select("agent-ui")
                    activity.setContent {
                        val width = LocalWindowInfo.current.containerSize.width
                        CompositionLocalProvider(LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f), LocalWorkbenchDark provides dark) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                val state by controller.state.collectAsState()
                                WorkbenchSheet(context.getString(R.string.ai_agent_settings), {}) { AiAgentSettings(state, controller) }
                            }
                        }
                    }
                }
                await("ready") { ai.get().state.value.configured && !ai.get().state.value.skillsLoading && !ai.get().state.value.permissionsLoading }
                Thread.sleep(500); instrumentation.waitForIdleSync()
                assertFalse(ai.get().state.value.automaticDelegation)
                switchNode { node ->
                    assertFalse(node.config.contains(SemanticsProperties.Disabled))
                    assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
                }
                await("switch applied") { ai.get().state.value.automaticDelegation }
                await("choice saved") { f.history.load("agent-ui").state?.automaticDelegation == true }
                f.enqueue("Simple response", responseDelayMillis = 2000)
                instrumentation.runOnMainSync { assertTrue(ai.get().send("Hello")); ai.get().setAutomaticDelegation(false) }
                await("task running") { ai.get().state.value.active }
                Thread.sleep(100); instrumentation.waitForIdleSync()
                switchNode { node -> assertTrue(node.config.contains(SemanticsProperties.Disabled)) }
                assertTrue(ai.get().state.value.automaticDelegation)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(context.cacheDir, "ui-review/agent-settings/${if (dark) "night" else "day"}.png").apply {
                        parentFile!!.mkdirs(); outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    }
                } finally { bitmap.recycle() }
                instrumentation.runOnMainSync { ai.get().stop() }
                await("stopped") { !ai.get().state.value.active }
            }
        }
    }
}
