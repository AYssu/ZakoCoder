package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.editor.EditorLanguages
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.warmDeviceActivity
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Owns only isolated buffers/files; no user projects, model settings or command execution. */
class EditorPreviewDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(60)
        }
    }
    /** Inspect the mounted Compose tree, without a stale platform accessibility snapshot. */
    private fun <T> nodes(scenario: ActivityScenario<MainActivity>, block: (List<SemanticsNode>) -> T): T {
        var result: T? = null
        scenario.onActivity { activity ->
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(activity.window.decorView)
            result = block(all)
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("\n") { it.text }.orEmpty()
    private fun visible(scenario: ActivityScenario<MainActivity>, label: String) = nodes(scenario) { all ->
        all.any { text(it) == label && it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }
    }
    private fun click(scenario: ActivityScenario<MainActivity>, label: String) {
        await("visible $label") { visible(scenario, label) }
        nodes(scenario) { all ->
            var node: SemanticsNode? = all.single { text(it) == label }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(label, requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        instrumentation.waitForIdleSync()
    }
    private fun editor(scenario: ActivityScenario<MainActivity>): CodeEditor? {
        var result: CodeEditor? = null
        fun visit(view: View) {
            if (view is CodeEditor) result = view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        scenario.onActivity { visit(it.window.decorView) }
        return result
    }

    @Test fun newBundledGrammarsActuallyTokenize() {
        EditorLanguages(context).prepare()
        val cases = mapOf(
            "batch" to "@echo off", "markdown" to "# Heading", "html" to "<div class=\"sample\">",
            "css" to "/* comment */", "yaml" to "name: sample", "toml" to "[section]",
            "xml" to "<root attr=\"value\">", "sql" to "SELECT name FROM users", "go" to "package main",
            "rust" to "fn main() {}", "dockerfile" to "FROM ubuntu:24.04", "makefile" to "# comment")
        cases.forEach { (language, source) ->
            val grammar = requireNotNull(GrammarRegistry.getInstance().findGrammar("source.zako.$language"))
            val result = grammar.tokenizeLine(source)
            assertFalse(language, result.isStoppedEarly)
            assertTrue("$language has classified tokens", result.tokens.any { it.scopes.size > 1 })
        }
        val batch = requireNotNull(GrammarRegistry.getInstance().findGrammar("source.zako.batch"))
        listOf("echo don't stop", "echo \"unfinished", "echo ^\"literal").forEach { source ->
            val first = batch.tokenizeLine(source)
            val next = batch.tokenizeLine("set count=2", first.ruleStack, java.time.Duration.ofSeconds(1))
            assertFalse(source, next.isStoppedEarly)
            assertTrue("BAT next line classified as keyword: $source", next.tokens.any { token -> token.scopes.any { it.startsWith("keyword") } })
            assertFalse("BAT string must not leak across lines: $source", next.tokens.any { token -> token.scopes.any { it.startsWith("string") } })
        }
    }

    @Test(timeout = 90000) fun markdownPreviewUsesUnsavedBufferAndPreservesUndoAndSelection() {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "editor-preview-$token").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val id = UUID.randomUUID().toString()
        val project = File(root, "projects/$id").apply { mkdirs() }
        val source = File(project, "readme.MD")
        val original = "# Original heading\r\n\r\n1. One\r\n2. Two\r\n\r\n| Name | Value |\r\n| --- | --- |\r\n| Row | Data |\r\n"
        source.writeText(original)
        lateinit var controller: EditorController
        var initialized = false
        try {
            instrumentation.runOnMainSync { controller = EditorController(isolated, ProjectRepository(isolated, File(root, "projects")), {}) }
            initialized = true
            await("editor ready") { !controller.state.value.loading }
            instrumentation.runOnMainSync { controller.open(id, "readme.MD") }
            await("document loaded") { controller.state.value.document != null }
            val buffer = requireNotNull(controller.state.value.document).content
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    scenario.onActivity { activity -> activity.setContent {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalWorkbenchDark provides dark, LocalDensity provides Density(density, 1.3f)) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { CodeEditorPage(controller) }
                            }
                        }
                    } }
                    await("native editor bound to current buffer") { editor(scenario)?.text === buffer }
                    instrumentation.runOnMainSync { controller.replaceAll("Original", "Edited") }
                    await("unsaved buffer updated before preview") {
                        controller.state.value.document?.content?.toString() == original.replace("Original", "Edited") && controller.state.value.dirty
                    }
                    val view = requireNotNull(editor(scenario))
                    instrumentation.runOnMainSync { view.setSelection(2, 2) }
                    click(scenario, context.getString(R.string.editor_mode_preview))
                    await("preview displays unsaved heading") { visible(scenario, "Edited heading") && editor(scenario) == null }
                    assertSame(buffer, controller.state.value.document!!.content)
                    assertTrue(controller.state.value.dirty)
                    assertEquals(original, source.readText())
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        try { File(context.cacheDir, "ui-review/editor-preview-${if (dark) "dark" else "light"}.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        } } finally { bitmap.recycle() }
                    }
                    click(scenario, context.getString(R.string.editor_mode_edit))
                    await("native editor restored") { editor(scenario)?.text === buffer }
                    val restored = requireNotNull(editor(scenario))
                    instrumentation.runOnMainSync {
                        assertEquals(2, restored.cursor.leftLine); assertEquals(2, restored.cursor.leftColumn)
                        assertTrue(restored.canUndo()); restored.undo(); controller.changed()
                    }
                    await("undo after preview") { controller.state.value.document!!.content.toString() == original }
                    assertFalse(controller.state.value.dirty)
                }
            }
        } finally {
            if (initialized) instrumentation.runOnMainSync { controller.close() }
            // Let the isolated empty-draft write finish before removing its owned directory.
            Thread.sleep(700)
            root.deleteRecursively()
            context.deleteSharedPreferences("$token-projects")
        }
    }
}
