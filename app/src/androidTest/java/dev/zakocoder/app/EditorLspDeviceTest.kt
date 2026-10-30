package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.lsp.EditorLspController
import dev.zakocoder.app.editor.lsp.LspPhase
import dev.zakocoder.app.editor.lsp.LspServer
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real guest stdio server; no HTTP fixture, no model calls, no credentials. */
class EditorLspDeviceTest {
    @Before fun foregroundForDeviceUi() = warmDeviceActivity()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(condition: () -> Boolean) = withTimeout(40_000) { while (!condition()) delay(100) }
    @Test(timeout = 150000) fun pythonDiagnosticsCompletionUnsavedChangesAndCloseUseRealServer() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val app = (context.applicationContext as ZakoApplication).workbench
            await { !app.state.value.loading && !app.state.value.busy }
            assumeTrue("Ubuntu is required for real language-server validation", app.state.value.installed)
            val detection = app.runtime.execute(LspServer.Python.detectScript, null, app.state.value.settings)
            assumeTrue("Python language-server package must be explicitly installed", detection.exitCode == 0)
            val project = withContext(Dispatchers.IO) { app.projects.create("lsp-owned-${UUID.randomUUID()}") }
            val directory = withContext(Dispatchers.IO) { app.projects.directory(project.id) }
            val source = File(directory, "completion fixture.py")
            withContext(Dispatchers.IO) { source.writeText("print('disk baseline remains unchanged')\n") }
            lateinit var view: CodeEditor
            lateinit var controller: EditorLspController
            var initialized = false
            try {
                withContext(Dispatchers.Main) {
                    scenario.onActivity { activity ->
                        view = CodeEditor(activity).apply { setText("import math\nunknown_fixture_symbol\nmath.sq\n") }
                        activity.setContentView(view)
                        controller = EditorLspController(context, app.runtime, { app.state.value.settings }, { app.state.value.installed })
                        initialized = true
                        controller.attach(view, project.id, source.name) { EmptyLanguage() }
                    }
                }
                await { controller.state.value.phase in setOf(LspPhase.Ready, LspPhase.Failed, LspPhase.MissingServer) }
                assertEquals(controller.state.value.toString(), LspPhase.Ready, controller.state.value.phase)
                await { controller.state.value.diagnosticCount > 0 }
                val labels = withTimeout(20000) { controller.completionLabels(2, 7) }
                assertTrue("Real Jedi completion contains sqrt: $labels", labels.any { it.substringBefore('(').trim() == "sqrt" })
                withContext(Dispatchers.Main) { view.setText("import math\nmath.sqrt(4)\n") }
                await { controller.state.value.diagnosticCount == 0 }
                withContext(Dispatchers.IO) { assertEquals("print('disk baseline remains unchanged')\n", source.readText()) }
                // Retry the SAME connected view with an unsupported document: connection
                // teardown must restore the base Language even though no new LSP is attached.
                withContext(Dispatchers.Main) { controller.attach(view, project.id, "unsupported.fixture") { EmptyLanguage() } }
                assertEquals(LspPhase.Unsupported, controller.state.value.phase)
                withContext(Dispatchers.Main) { assertTrue("Failed same-view retry restores base syntax", view.editorLanguage is EmptyLanguage) }
                withContext(Dispatchers.Main) { controller.detach() }
                assertEquals(LspPhase.Idle, controller.state.value.phase)
                withContext(Dispatchers.Main) { assertTrue("Detach restores syntax immediately", view.editorLanguage is EmptyLanguage) }
                delay(300)
                withContext(Dispatchers.Main) { assertTrue("Async disposal must not reinstall LSP", view.editorLanguage is EmptyLanguage) }
            } finally {
                withContext(Dispatchers.Main) { if (initialized) controller.close() }
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
            }
        }
    }
}
