package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual permission sheet, isolated project, explicit/cancelled confirmation, day/night captures. */
class AiYoloPermissionUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
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
    private fun has(label: String) = nodes { it.any { node -> text(node) == label } }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 30000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(50)
        }
    }
    private fun click(label: String) {
        await("Control: $label") { has(label) }
        nodes { all ->
            var node: SemanticsNode? = all.first { text(it) == label }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue("Clickable: $label", requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(base.filesDir, "screen-review/product-polish/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 180000) fun explicitYoloConfirmationCancelPersistenceAndBothThemeScreenshots() = runBlocking {
        val token = "yolo-ui-${UUID.randomUUID()}"
        val own = File(base.cacheDir, token)
        val preferences = mutableSetOf<String>()
        val originalContext = base
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = originalContext.getSharedPreferences("$token-$name".also { preferences += it }, mode)
        }
        val workbench = withContext(Dispatchers.Main) { Workbench(isolated) }
        var dark by mutableStateOf(false)
        try {
            await("Workbench ready") { !workbench.state.value.loading }
            instrumentation.runOnMainSync { workbench.create("移动开发项目") }
            await("Project created") { !workbench.state.value.busy && workbench.state.value.project != null }
            val project = requireNotNull(workbench.state.value.project)
            instrumentation.runOnMainSync { workbench.ai.select(project.id) }
            await("Policy loaded") { !workbench.ai.state.value.permissionsLoading }
            assertFalse(workbench.ai.state.value.policy.yolo)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                            Surface(Modifier.fillMaxSize().semantics { stateDescription = if (dark) "yolo-dark" else "yolo-day" }) {
                                AiPermissionsSheet(workbench, project.name) {}
                            }
                        }
                    }
                } }
                listOf(false, true).forEach { isDark ->
                    instrumentation.runOnMainSync { dark = isDark; workbench.ai.setPolicy(AiPolicy()) }
                    await("Ordinary policy saved") { !workbench.ai.state.value.permissionsSaving && !workbench.ai.state.value.policy.yolo }
                    await("Theme switched") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.StateDescription) == if (isDark) "yolo-dark" else "yolo-day" } } }
                    val mode = if (isDark) "night" else "day"
                    val yoloLabel = base.getString(R.string.ai_permission_preset_yolo)
                    await("YOLO row visible") { has(yoloLabel) }
                    screenshot("permissions-$mode")
                    click(yoloLabel)
                    await("Explicit all-access warning") { has(base.getString(R.string.ai_permission_yolo_confirm_title)) }
                    assertFalse("Selection alone must not enable YOLO", workbench.ai.state.value.policy.yolo)
                    screenshot("permissions-yolo-confirm-$mode")
                    click(base.getString(R.string.cancel))
                    await("Cancel preserves ordinary policy") { !has(base.getString(R.string.ai_permission_yolo_confirm_title)) }
                    assertFalse(workbench.ai.state.value.policy.yolo)
                    click(yoloLabel)
                    click(base.getString(R.string.ai_auto_allow_confirm))
                    await("YOLO saved") { !workbench.ai.state.value.permissionsSaving && workbench.ai.state.value.policy.yolo }
                    await("Current mode visible") { has(base.getString(R.string.ai_permission_preset_current, yoloLabel)) }
                    assertEquals(workbench.ai.state.value.policy, withContext(Dispatchers.IO) { AiPermissionStore(isolated).load(project.id) })
                    screenshot("permissions-yolo-enabled-$mode")
                    click(base.getString(R.string.ai_permission_preset_normal))
                    await("Can revoke without extra warning") { !workbench.ai.state.value.permissionsSaving && workbench.ai.state.value.policy == AiPolicy() }
                }
            }
        } finally {
            withContext(NonCancellable) {
                instrumentation.runOnMainSync { workbench.stopAll() }
                delay(200)
                withContext(Dispatchers.IO) { PrivateFiles.deleteTree(own); preferences.forEach { originalContext.deleteSharedPreferences(it) } }
            }
        }
    }
}
