package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual transfer sheets with isolated histories. Document pickers and external services are never launched. */
@SdkSuppress(minSdkVersion = 30)
class AiConversationTransferUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 90_000) fun dayTransferModesShowTheirOwnAvailableActions() = render(false)
    @Test(timeout = 90_000) fun nightTransferModesShowTheirOwnAvailableActions() = render(true)

    private fun render(dark: Boolean) = runBlocking {
        warmDeviceActivity()
        org.junit.Assume.assumeTrue("Isolated test package only", context.packageName.endsWith(".runtimecheck"))
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "transfer-ui-$token").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val history = AiHistoryStore(File(root, "history"))
        val project = "transfer-ui-$token"
        val original = AiState(turn = 1, timeline = listOf(
            AiEntry("fixture-user", "Continue implementing the project after reviewing its tests.", role = AiRole.User, turn = 1),
            AiEntry("fixture-assistant", "The source changes are ready for review; device validation remains pending.", role = AiRole.Assistant, turn = 1)))
        withContext(Dispatchers.IO) { history.save(project, original) }
        val ai = withContext(Dispatchers.Main) {
            AiController(isolated, { File(root, "workspace").apply { mkdirs() } }, { _, _ -> false },
                { _, _ -> error("Screenshots must not execute commands") }, {}, {},
                config = AiConfigStore(isolated, "unused-ui-provider", "unused-ui-$token"), history = history)
                .also { it.select(project) }
        }
        try {
            withTimeout(20_000) { while (ai.state.value.permissionsLoading || ai.state.value.skillsLoading || ai.models.state.value.loading) delay(20) }
            assertFalse("No provider settings should be loaded for this fixture", ai.state.value.configured)
            assertEquals(original.conversationId, ai.state.value.conversationId)
            val mode = mutableStateOf(AiSlashCommandId.Export)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                    activity.setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark) {
                            WorkbenchMiuixTheme(dark) {
                                Surface(Modifier.fillMaxSize()) {
                                    val state by ai.state.collectAsStateWithLifecycle()
                                    key(mode.value) { AiConversationTransferSheet(ai, state, mode.value, "Mobile demo", onDismiss = {}) }
                                }
                            }
                        }
                    }
                }
                val modes = listOf(
                    Triple(AiSlashCommandId.Export, R.string.conversation_export, "export"),
                    Triple(AiSlashCommandId.Import, R.string.conversation_import, "import"),
                    Triple(AiSlashCommandId.Recap, R.string.conversation_recap, "recap"))
                modes.forEach { (action, title, name) ->
                    scenario.onActivity { mode.value = action }
                    await("Transfer title is visible") { shown(context.getString(title)) }
                    val actions = when (action) {
                        AiSlashCommandId.Export -> listOf(R.string.conversation_export_markdown, R.string.conversation_export_json)
                        AiSlashCommandId.Import -> listOf(R.string.conversation_choose_file)
                        else -> listOf(R.string.conversation_save_recap)
                    }
                    actions.forEach { label -> await("Transfer action is visible and enabled") {
                        val text = context.getString(label)
                        shown(text) && hasEnabledClickAction(text)
                    } }
                    if (action == AiSlashCommandId.Import) {
                        assertTrue(shown(context.getString(R.string.conversation_import_target, "Mobile demo")))
                        assertFalse("Import must not be confirmed before a document is selected", shown(context.getString(R.string.conversation_import_confirm)))
                    }
                    settleAndCapture(dark, name)
                }
                assertEquals(original.conversationId, ai.state.value.conversationId)
                assertFalse(ai.state.value.active)
                withContext(Dispatchers.IO) { assertEquals(1, history.listConversations(project).size) }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { ai.stop() }
            withContext(NonCancellable + Dispatchers.IO) {
                root.deleteRecursively()
                isolated.getSharedPreferences("first-run-setup", 0).edit().clear().commit()
            }
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
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        val display = context.resources.displayMetrics
        return bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < display.widthPixels && bounds.top < display.heightPixels
    }
    private fun shown(text: String) = nodes { all -> all.any { label(it) == text && visible(it) } }
    private fun hasEnabledClickAction(text: String) = nodes { all ->
        var node = all.lastOrNull { label(it) == text && visible(it) }
        var clickable = false
        var disabled = false
        while (node != null) {
            if (node.config.getOrNull(SemanticsActions.OnClick)?.action != null) clickable = true
            if (node.config.contains(SemanticsProperties.Disabled)) disabled = true
            node = node.parent
        }
        clickable && !disabled
    }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { description }; Thread.sleep(50) }
    }
    private fun settleAndCapture(dark: Boolean, name: String) {
        var previous = emptyList<Pair<String, Int>>()
        var stable = 0
        await("Transfer sheet has settled") {
            val positions = nodes { all -> all.filter { label(it).isNotBlank() && visible(it) }.map { label(it) to it.boundsInWindow.top.toInt() } }
            stable = if (positions == previous) stable + 1 else 0
            previous = positions
            stable >= 5
        }
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/conversation-transfer/$name-${if (dark) "night" else "day"}.png")
                .apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
