package dev.zakocoder.app

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.mcp.*
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real isolated controller errors, native import form and management page; no user configuration. */
@SdkSuppress(minSdkVersion = 30)
class McpFailureUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() {
        val component = ComponentName(context.packageName, MainActivity::class.java.name).flattenToString()
        require(component.matches(Regex("[A-Za-z0-9_./]+")))
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $component").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
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
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(label: String) = nodes { it.any { node -> text(node) == label } }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(label)
            Thread.sleep(40)
        }
    }
    private fun click(label: String) {
        await("Control: $label") { has(label) }
        nodes { all ->
            var node = all.last { text(it) == label } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    @Test(timeout = 120000) fun lightErrorsRetainDraftShowCauseAndCopy() = review(false)
    @Test(timeout = 120000) fun darkErrorsRetainDraftShowCauseAndCopy() = review(true)

    private fun review(dark: Boolean) = runBlocking {
        val root = File(context.cacheDir, "mcp-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val alias = "mcp-ui-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = McpController(isolated, scope, { _, _ -> error("No process") }, keyAlias = alias)
        val closed = AtomicBoolean()
        try {
            withTimeout(10000) { while (controller.state.value.loading || controller.state.value.busy) delay(10) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun show(importing: Boolean) {
                    scenario.onActivity { activity -> activity.setContent {
                        val current = LocalConfiguration.current
                        val configuration = remember(current, dark) { Configuration(current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        } }
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize()) {
                                    if (importing) McpImportDialog(controller, true) { closed.set(true) }
                                    else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                        McpManagementPanel(controller, "fixture-project", true, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } }
                }
                show(true)
                await("Import source field") { nodes { all -> all.count { it.config.getOrNull(SemanticsActions.SetText) != null } >= 2 } }
                val drafts = listOf("""{"token":"ui-fixture-only",}""" to "JsonSyntax",
                    """{"type":"http","url":"http:///mcp"}""" to "Url")
                for ((draft, reason) in drafts) {
                    nodes { all -> assertTrue(all.last { it.config.getOrNull(SemanticsActions.SetText) != null }
                        .config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true) }
                    click(context.getString(R.string.mcp_import))
                    await("Specific import reason $reason") { controller.state.value.error.contains("MCP_$reason") }
                    val message = controller.state.value.error
                    await("Visible reason $reason") { has(message) }
                    assertFalse(closed.get())
                    assertTrue(nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.EditableText)?.text?.length == draft.length } })
                    assertFalse(message.contains("ui-fixture-only"))
                    capture(scenario, dark, "import-$reason")
                }
                MockWebServer().use { http ->
                    http.enqueue(MockResponse().setResponseCode(401).setBody("never-display-upstream-fixture"))
                    val server = McpServer(name = "Review MCP", transport = McpTransport.StreamableHttp,
                        url = http.url("/mcp").toString(), enabled = true)
                    controller.save(server).join()
                    controller.probe("fixture-project", server.id).join()
                    assertTrue(controller.state.value.error.contains("HTTP 401"))
                    assertFalse(controller.state.value.error.contains("never-display"))
                    show(false)
                    await("Authentication message in management page") { has(controller.state.value.error) }
                    capture(scenario, dark, "connection-401")
                    click(context.getString(R.string.mcp_error_copy))
                    await("Clipboard contains full diagnostic") {
                        val copied = AtomicReference<String>()
                        instrumentation.runOnMainSync {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            copied.set(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty())
                        }
                        copied.get() == controller.state.value.error
                    }

                }
            }
        } finally {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val insets = AtomicReference<android.graphics.Insets>()
        scenario.onActivity { insets.set(it.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())) }
        val raw = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val bars = requireNotNull(insets.get())
        val cropped = Bitmap.createBitmap(raw, bars.left, bars.top, raw.width - bars.left - bars.right, raw.height - bars.top - bars.bottom)
        try {
            // Sample the panel surface, away from the full-width blue bottom action.
            val pixel = cropped.getPixel(8, cropped.height / 2)
            val brightness = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
            assertTrue("Actual theme brightness=$brightness", if (dark) brightness < 100 else brightness > 120)
            File(context.cacheDir, "ui-review/mcp-errors/${if (dark) "dark" else "light"}-$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { if (cropped !== raw) cropped.recycle(); raw.recycle() }
    }
}
