package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.kernel.*
import dev.zakocoder.app.settings.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual production components, synthetic subscription data; never exposes a real subscription. */
class ProxyRecoveryUiDeviceTest {
    private fun nodes(): List<SemanticsNode> = buildList {
        fun collect(node: SemanticsNode) { add(node); node.children.forEach(::collect) }
        fun visit(view: android.view.View) {
            if (view is ViewRootForTest && view.isShown) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun action(node: SemanticsNode): SemanticsNode? {
        var current: SemanticsNode? = node
        while (current != null) {
            if (current.config.getOrNull(SemanticsActions.OnClick) != null) return current
            current = current.parent
        }
        return null
    }
    @Test(timeout = 90_000) fun recoveryActionsAndNoticesInBothThemes() {
        warmDeviceActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val plugins = KernelPluginManager(context)
        plugins.recordFailure(KernelBindingFailure.Rejected)
        val diagnostic = plugins.diagnostics()
        val vless = requireNotNull(ProxySubscriptionParser().parseLink(
            "vless://11111111-1111-4111-8111-111111111111@edge.example:443?type=tcp&encryption=none&security=tls&sni=www.example.com#VLESS%20%E6%B5%8B%E8%AF%95%E8%8A%82%E7%82%B9"))
        val notice = ProxyNode("notice", "请到官网下载客户端", ProxyProtocol.Vmess, "127.0.0.1", 443, subscriptionPlaceholder = true)
        var dark by mutableStateOf(false)
        var showDiagnostic by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var activated = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(context.getString(R.string.proxy_page_title), style = MaterialTheme.typography.headlineSmall)
                                KernelPluginCard(KernelPluginStatus.Installed(KernelPluginInfo("1.0.2-dev", 3)),
                                    false, false, null, null, false, false, false, {}, {}, {}, {},
                                    bindingFailure = KernelBindingFailure.Rejected, onActivate = { activated++ },
                                    onDiagnostics = { showDiagnostic = true }, bundled = false)
                                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                                    Column(Modifier.padding(8.dp)) {
                                        ProxyNodeRow(notice, false, true, { error("Notice selected") }, { error("Notice acted") })
                                        HorizontalDivider()
                                        ProxyNodeRow(vless, selected, true, { selected = true }, { error("VLESS unsupported") })
                                    }
                                }
                            }
                            if (showDiagnostic) KernelDiagnosticsSheet(diagnostic) { showDiagnostic = false }
                        }
                    }
                }
            } }
            fun click(label: String) = instrumentation.runOnMainSync {
                val found = nodes().first { text(it) == label }
                assertTrue(requireNotNull(action(found)?.config?.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            }
            fun capture(name: String) {
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                    File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            repeat(2) { theme ->
                instrumentation.runOnMainSync { dark = theme == 1; showDiagnostic = false; selected = false }
                Thread.sleep(600)
                instrumentation.runOnMainSync {
                    assertNull("Notice must not expose a click action", action(nodes().first { text(it) == notice.name }))
                    assertNotNull("Real VLESS remains selectable", action(nodes().first { text(it) == vless.name }))
                }
                click(vless.name)
                instrumentation.runOnMainSync { assertTrue(selected) }
                click(context.getString(R.string.proxy_plugin_activate))
                capture("proxy-recovery-${if (dark) "dark" else "light"}.png")
                click(context.getString(R.string.proxy_plugin_diagnostics))
                Thread.sleep(500)
                click(context.getString(R.string.proxy_plugin_diagnostics_copy))
                // Android's clipboard overlay obscures the components under review.
                Thread.sleep(6_000)
                capture("proxy-diagnostic-${if (dark) "dark" else "light"}.png")
            }
            assertEquals(2, activated)
        }
    }
}
