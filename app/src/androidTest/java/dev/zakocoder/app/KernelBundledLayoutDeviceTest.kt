package dev.zakocoder.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.kernel.*
import dev.zakocoder.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KernelBundledLayoutDeviceTest {
    @Test(timeout = 60000) fun bundledCardHasVersionAndDiagnosticsWithoutPluginActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                scenario.onActivity { activity -> activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors,
                            typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp)) {
                                    KernelPluginCard(KernelPluginStatus.Installed(KernelPluginInfo(BuildConfig.KERNEL_VERSION, 1)),
                                        false, false, null, null, false, false, false,
                                        { error("Must not install") }, { error("Must not import") }, {},
                                        { error("Must not remove") }, onActivate = { error("Must not activate") }, bundled = true)
                                }
                            }
                        }
                    }
                } }
                instrumentation.waitForIdleSync()
                // setContent schedules a Compose frame after the Activity callback/idle barrier.
                // Match the established card screenshot test, then wait for the actual semantics.
                Thread.sleep(600)
                val expectedTitle = context.getString(R.string.proxy_bundled_kernel_title)
                var texts = emptyList<String>()
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                do {
                    instrumentation.runOnMainSync {
                        val captured = mutableListOf<String>()
                        fun collect(node: SemanticsNode) {
                            captured.addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
                            node.children.forEach(::collect)
                        }
                        fun visit(view: android.view.View) {
                            if (view is ViewRootForTest && view.isShown) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                            if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                        }
                        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
                        texts = captured.toList()
                    }
                    if (texts.contains(expectedTitle)) break
                    Thread.sleep(100)
                } while (android.os.SystemClock.uptimeMillis() < deadline)
                val diagnostic = "theme=${if (dark) "night" else "day"}; texts=$texts"
                assertTrue("Missing bundled title; $diagnostic", texts.contains(expectedTitle))
                assertTrue("Missing bundled version; $diagnostic",
                    texts.contains(context.getString(R.string.proxy_bundled_kernel_version, BuildConfig.KERNEL_VERSION)))
                assertTrue("Missing diagnostics action; $diagnostic",
                    texts.contains(context.getString(R.string.proxy_plugin_diagnostics)))
                for (id in listOf(R.string.proxy_plugin_update_online, R.string.proxy_plugin_update_local,
                    R.string.proxy_plugin_uninstall, R.string.proxy_plugin_activate, R.string.proxy_plugin_install_online,
                    R.string.proxy_plugin_install_local)) {
                    assertFalse("External plugin action must stay hidden: ${context.getString(id)}; $diagnostic",
                        texts.contains(context.getString(id)))
                }
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                    File(context.getExternalFilesDir(null), "kernel-bundled-${if (dark) "night" else "day"}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }
}
