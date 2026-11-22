package dev.zakocoder.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Painted native Markdown, including wrapped inline code; no model or saved project. */
class InlineCodeBackgroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun measured(fragment: String): Pair<SemanticsNode, TextLayoutResult>? = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        all.firstNotNullOfOrNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.firstOrNull { fragment in it.layoutInput.text.text }?.let { node to it }
        }
    }

    private fun awaitLayout(fragment: String): Pair<SemanticsNode, TextLayoutResult> {
        val end = SystemClock.uptimeMillis() + 12_000
        while (true) {
            measured(fragment)?.let { return it }
            assertTrue("Markdown becomes measurable", SystemClock.uptimeMillis() < end)
            Thread.sleep(40)
        }
    }

    @Test fun inlineCodeHasRoundedCornersAndWrapsWithoutReplacingSelectableText() {
        val dark = mutableStateOf(false)
        val surface = AtomicReference<Color>()
        val background = AtomicReference<Color>()
        val source = """
            普通文字中的 `inline_code` 使用轻圆角底色。

            长路径 `app/src/main/java/dev/zakocoder/app/ui/InlineCodeBackground.kt` 自动换行，保留原文。

            **强调**、[链接](https://example.com)和 `code` 仍然可以选中。

            | 文件 | 说明 |
            | --- | --- |
            | `README.md` | 项目说明 |
            | `app/build.gradle.kts` | 构建配置 |
        """.trimIndent()
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(dark.value) {
                    surface.set(MaterialTheme.colorScheme.surface)
                    background.set(MaterialTheme.colorScheme.surfaceContainerHigh)
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().padding(20.dp)) { AiMarkdown(source, onLink = {}) }
                    }
                }
            } }
            for (night in listOf(false, true)) {
                main { dark.value = night }
                val (node, layout) = awaitLayout("inline_code")
                val rect = main { inlineCodeBackgrounds(layout).single() }
                val range = layout.layoutInput.text.getStringAnnotations(INLINE_CODE_ANNOTATION, 0, layout.layoutInput.text.length).single()
                assertEquals("Code remains part of native text", "inline_code", layout.layoutInput.text.text.substring(range.start, range.end))
                val (_, wrapped) = awaitLayout("InlineCodeBackground.kt")
                assertTrue("Wrapped code gets separate visual backgrounds", main { inlineCodeBackgrounds(wrapped).size } > 1)
                Thread.sleep(250)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val padding = 3f * context.resources.displayMetrics.density
                    val x = (node.boundsInWindow.left + rect.left - padding).toInt()
                    val y = (node.boundsInWindow.top + rect.top).toInt()
                    fun close(actual: Int, expected: Color) = listOf(16, 8, 0).all { shift ->
                        kotlin.math.abs(((actual shr shift) and 255) - ((expected.toArgb() shr shift) and 255)) <= 3
                    }
                    assertTrue("Rounded corner exposes underlying surface", close(bitmap.getPixel(x + 1, y + 1), surface.get()))
                    assertTrue("Middle of code retains its background", close(bitmap.getPixel(x + rect.width.toInt() / 2, y + 1), background.get()))
                    val middleY = (node.boundsInWindow.top + rect.center.y).toInt()
                    assertTrue("Left padding has background before the first glyph",
                        close(bitmap.getPixel((node.boundsInWindow.left + rect.left - padding / 2).toInt(), middleY), background.get()))
                    assertTrue("Right padding has background after the last glyph",
                        close(bitmap.getPixel((node.boundsInWindow.left + rect.right + padding / 2).toInt(), middleY), background.get()))
                    val directory = File(context.cacheDir, "ui-review/markdown-code").apply { mkdirs() }
                    File(directory, "${if (night) "night" else "day"}-rounded.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
}
