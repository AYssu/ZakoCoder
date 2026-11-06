package dev.zakocoder.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.kernel.KernelPluginInfo
import dev.zakocoder.app.kernel.KernelPluginStatus
import dev.zakocoder.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real plugin card in a constrained viewport; callbacks never install or remove packages. */
class KernelPluginLayoutDeviceTest {
    @Test(timeout = 90000) fun updatesFitAndUninstallHasSeparateTouchTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var online = 0; var local = 0; var removed = 0
        fun nodes(): List<SemanticsNode> = buildList {
            fun collect(node: SemanticsNode) { add(node); node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (view is ViewRootForTest && view.isShown) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors,
                            typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp)) {
                                    KernelPluginCard(KernelPluginStatus.Installed(KernelPluginInfo("0.1.1", 1007)),
                                        false, false, null, null, false, false, false,
                                        { online++ }, { local++ }, {}, { removed++ }, bundled = false)
                                }
                            }
                        }
                    }
                } }
                instrumentation.waitForIdleSync()
                Thread.sleep(600)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val file = File(context.getExternalFilesDir(null),
                        "plugin-layout-${if (dark) "night" else "day"}-normal.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                instrumentation.runOnMainSync {
                    val labels = listOf(R.string.proxy_plugin_update_online, R.string.proxy_plugin_update_local,
                        R.string.proxy_plugin_uninstall).map(context::getString)
                    val texts = labels.map { label -> nodes().first { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                    } }
                    val buttons = texts.map { text ->
                        var node = text
                        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                        node
                    }
                    texts.forEach { node ->
                        val layouts = mutableListOf<TextLayoutResult>()
                        requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts)
                        // Compose rounds pixel widths to Int; allow at most one rounding pixel.
                        assertTrue("Label must fit: ${node.config.getOrNull(SemanticsProperties.Text)}",
                            layouts.isNotEmpty() && layouts.all { result ->
                                !result.didOverflowHeight && (0 until result.lineCount).all { line ->
                                    result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 1f
                                }
                            })
                    }
                    assertTrue(buttons[0].boundsInRoot.right <= buttons[1].boundsInRoot.left)
                    assertTrue(buttons.take(2).all { it.boundsInRoot.bottom <= buttons[2].boundsInRoot.top })
                    buttons.forEach { assertTrue(requireNotNull(it.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()) }
                }

            }
            assertEquals(2, online); assertEquals(2, local); assertEquals(2, removed)
        }
    }
}
