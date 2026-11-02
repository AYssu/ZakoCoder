package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.mcp.*
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Uses only isolated test credentials, encrypted controller storage and the production form. */
@SdkSuppress(minSdkVersion = 30)
class McpCredentialsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 120000) fun dayBearerFormAndProviderNames() = exercise(false)
    @Test(timeout = 120000) fun nightBearerFormAndProviderNames() = exercise(true)

    private fun exercise(dark: Boolean) = runBlocking {
        val root = File(context.cacheDir, "mcp-credential-review-${UUID.randomUUID()}").apply { mkdirs() }
        val alias = "mcp-credential-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = McpController(isolated, scope, { _, _ -> error("Form must not launch a process") }, keyAlias = alias)
        try {
            await { !controller.state.value.loading }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val closed = AtomicBoolean()
                var presentation = 0
                fun show(server: McpServer) {
                    presentation++
                    val mounted = AtomicBoolean()
                    closed.set(false)
                    scenario.onActivity { activity -> activity.setContent { ReviewTheme(dark) {
                        key(presentation) {
                            McpServerEditor(controller, server, true) { closed.set(true) }
                            SideEffect { mounted.set(true) }
                        }
                    } } }
                    await { mounted.get() && has(context.getString(R.string.mcp_bearer_token)) }
                    instrumentation.waitForIdleSync()
                }
                val initial = McpServer(name = "Credential fixture", transport = McpTransport.StreamableHttp,
                    url = "http://127.0.0.1:32123/mcp", headers = mapOf("X-Client" to "fixture"))
                show(initial)
                field(R.string.mcp_bearer_token, "fixture-only-token")
                capture("mcp-bearer-${if (dark) "night" else "day"}")
                click(context.getString(R.string.mcp_save))
                await { closed.get() && !controller.state.value.busy }
                val saved = controller.state.value.servers.single()
                assertFalse(saved.enabled)
                assertEquals("Bearer fixture-only-token", saved.headers["Authorization"])
                assertEquals("fixture", saved.headers["X-Client"])
                show(saved)
                field(R.string.mcp_bearer_token, "")
                click(context.getString(R.string.mcp_save))
                await { closed.get() && !controller.state.value.busy }
                assertFalse(controller.state.value.servers.single().headers.keys.any { it.equals("Authorization", true) })
                show(saved)
                field(R.string.mcp_url, "http://other.example.invalid:32123/mcp")
                await { has(context.getString(R.string.mcp_credentials_cleared)) }
                click(context.getString(R.string.mcp_save))
                await { closed.get() && !controller.state.value.busy }
                assertTrue(controller.state.value.servers.single().headers.isEmpty())
                for (id in listOf(R.string.ai_provider_qwen, R.string.ai_provider_siliconflow, R.string.ai_provider_kimi)) {
                    assertFalse(context.getString(id).contains("中国"))
                }
                val scroll = ScrollState(0)
                scenario.onActivity { activity -> activity.setContent { ReviewTheme(dark) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                        AiProviderPresetList(true) { }
                    }
                } } }
                await { has(context.getString(R.string.ai_provider_qwen)) }
                val top = nodes { all -> all.first { text(it) == context.getString(R.string.ai_provider_qwen) }.layoutInfo.coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero).y }
                withContext(Dispatchers.Main) { scroll.scrollTo((top - 100).toInt().coerceAtLeast(0)) }
                capture("provider-names-${if (dark) "night" else "day"}")
            }
        } finally {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }
    @Composable private fun ReviewTheme(dark: Boolean, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalWorkbenchDark provides dark, LocalDensity provides Density(density.density, 1.3f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Box(Modifier.widthIn(max = 360.dp)) { content() } }
            }
        }
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(label: String) = nodes { it.any { n -> text(n) == label } }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!predicate()) { check(SystemClock.uptimeMillis() < deadline) { "Credential UI condition timed out" }; Thread.sleep(40) }
    }
    private fun field(id: Int, value: String) {
        val label = context.getString(id)
        nodes { all ->
            val field = all.firstOrNull { n -> n.config.getOrNull(SemanticsActions.SetText) != null && n.children.any { text(it) == label } }
                ?: all.first { n -> n.config.getOrNull(SemanticsActions.SetText) != null && text(n).contains(label) }
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
        instrumentation.waitForIdleSync()
    }
    private fun click(label: String) {
        await { has(label) }
        nodes { all ->
            var node: SemanticsNode? = all.last { text(it) == label }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val list = mutableListOf<SemanticsNode>()
            fun collect(n: SemanticsNode) { list += n; n.children.forEach(::collect) }
            fun visit(v: android.view.View) {
                if (!v.isShown) return
                if (v is ViewRootForTest) collect(v.semanticsOwner.unmergedRootSemanticsNode)
                if (v is android.view.ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(list))
        }
        return result.get()
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, "product-review/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
