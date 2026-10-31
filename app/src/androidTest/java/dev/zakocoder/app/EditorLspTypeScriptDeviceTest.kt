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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Ubuntu TypeScript service, including URI encoding and unsaved document lifecycle. */
class EditorLspTypeScriptDeviceTest {
    @Before fun foregroundForDeviceUi() = warmDeviceActivity()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(stage: String, detail: () -> String, condition: () -> Boolean) {
        android.util.Log.i("ZakoLspTypeScriptTest", "Starting stage: $stage")
        try { withTimeout(50_000) { while (!condition()) delay(100) } }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AssertionError("TypeScript LSP stage timed out: $stage; ${detail()}", timeout)
        }
        android.util.Log.i("ZakoLspTypeScriptTest", "Completed stage: $stage; ${detail()}")
    }
    private fun detail(controller: EditorLspController): String = controller.state.value.let {
        "phase=${it.phase}, server=${it.server}, diagnostics=${it.diagnosticCount}, code=${it.code}, stderr=${it.stderr.takeLast(2000)}"
    }
    @Test(timeout = 180000) fun typeScriptDiagnosticsCompletionAndUnsavedFixUseRealServer() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val app = (context.applicationContext as ZakoApplication).workbench
            await("workbench_ready", { "loading=${app.state.value.loading}, busy=${app.state.value.busy}, installed=${app.state.value.installed}" }) { !app.state.value.loading && !app.state.value.busy }
            assumeTrue("Ubuntu is required for real language-server validation", app.state.value.installed)
            val detection = app.runtime.execute(LspServer.TypeScript.detectScript, null, app.state.value.settings)
            assumeTrue("TypeScript language-server package must be explicitly installed", detection.exitCode == 0)
            val project = withContext(Dispatchers.IO) { app.projects.create("lsp-ts-owned-${UUID.randomUUID()}") }
            val directory = withContext(Dispatchers.IO) { app.projects.directory(project.id) }
            val source = File(directory, "completion # fixture.ts")
            val baseline = "export const diskBaseline = 1;\n"
            withContext(Dispatchers.IO) {
                source.writeText(baseline)
                File(directory, "tsconfig.json").writeText("""{"compilerOptions":{"strict":true,"noEmit":true,"target":"ES2020"},"include":["*.ts"]}""")
            }
            lateinit var view: CodeEditor
            lateinit var controller: EditorLspController
            var initialized = false
            try {
                withContext(Dispatchers.Main) {
                    scenario.onActivity { activity ->
                        view = CodeEditor(activity).apply { setText("const amount: number = 'fixture error';\nMath.sq\n") }
                        activity.setContentView(view)
                        controller = EditorLspController(context, app.runtime, { app.state.value.settings }, { app.state.value.installed })
                        initialized = true
                        controller.attach(view, project.id, source.name) { EmptyLanguage() }
                    }
                }
                await("server_initialize", { detail(controller) }) { controller.state.value.phase in setOf(LspPhase.Ready, LspPhase.Failed, LspPhase.MissingServer) }
                assertEquals(controller.state.value.toString(), LspPhase.Ready, controller.state.value.phase)
                await("initial_unsaved_diagnostics", { detail(controller) }) { controller.state.value.diagnosticCount > 0 }
                android.util.Log.i("ZakoLspTypeScriptTest", "Starting stage: Math.sqrt_completion")
                val labels = try { withTimeout(20000) { controller.completionLabels(1, 7) } }
                catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw AssertionError("TypeScript LSP stage timed out: Math.sqrt_completion; ${detail(controller)}", timeout)
                }
                android.util.Log.i("ZakoLspTypeScriptTest", "Completed stage: Math.sqrt_completion; ${detail(controller)}")
                assertTrue("Actual TypeScript Math completion contains sqrt: $labels", labels.any { it.substringBefore('(').trim() == "sqrt" })
                withContext(Dispatchers.Main) { view.setText("const amount: number = 4;\nMath.sqrt(amount);\n") }
                await("fixed_unsaved_diagnostics_cleared", { detail(controller) }) { controller.state.value.diagnosticCount == 0 }
                withContext(Dispatchers.IO) { assertEquals(baseline, source.readText()) }
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
