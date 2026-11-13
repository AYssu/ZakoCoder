package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.lsp.LspWireAdapter
import dev.zakocoder.app.editor.lsp.UbuntuLspConnection
import dev.zakocoder.app.editor.lsp.attachLspDiagnostics
import dev.zakocoder.app.runtime.RuntimeCommand
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the published editor and LSP binaries without a network or installed server. */
class SoraCompatibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 30000) fun stringAndMarkupDiagnosticsRenderReplaceAndClear() = runBlocking {
        val project = LspProject("/workspace/sora-compatibility")
        val document = project.createEditor("/workspace/sora-compatibility/fixture.py")
        lateinit var view: CodeEditor
        var removeDiagnostics: (() -> Unit)? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    view = CodeEditor(activity).apply { setText("missing_symbol\n") }
                    activity.setContentView(view)
                    document.editor = view
                    document.eventManager.init()
                    assertFalse(document.isEnableInlayHint)
                    removeDiagnostics = attachLspDiagnostics(view, document)
                }
                suspend fun expectMessage(expected: String?) {
                    withTimeout(10000) {
                        while (true) {
                            var matches = false
                            instrumentation.runOnMainSync {
                                val regions = mutableListOf<DiagnosticRegion>()
                                view.diagnostics?.queryInRegion(regions, 0, view.text.length)
                                matches = if (expected == null) regions.isEmpty()
                                    else regions.size == 1 && regions.single().detail?.detailedMessage?.toString() == expected
                            }
                            if (matches) break
                            delay(50)
                        }
                    }
                }
                val diagnostic = Diagnostic().apply {
                    range = Range(Position(0, 0), Position(0, 14))
                    severity = DiagnosticSeverity.Error
                    setMessage("plain diagnostic")
                }
                fun publish(diagnostics: List<Diagnostic>) = instrumentation.runOnMainSync {
                    project.diagnosticsContainer.setDiagnostics(document.uri, diagnostics)
                    document.onDiagnosticsUpdate()
                }
                publish(listOf(diagnostic))
                expectMessage("plain diagnostic")
                val markup = Diagnostic().apply {
                    range = Range(Position(0, 0), Position(0, 14))
                    severity = DiagnosticSeverity.Warning
                    setMessage(MarkupContent("markdown", "**markup diagnostic**"))
                }
                publish(listOf(markup))
                expectMessage("**markup diagnostic**")
                publish(emptyList())
                expectMessage(null)
                publish(listOf(diagnostic))
                expectMessage("plain diagnostic")
                instrumentation.runOnMainSync { removeDiagnostics?.invoke(); removeDiagnostics = null }
                expectMessage(null)
            } finally {
                instrumentation.runOnMainSync { removeDiagnostics?.invoke() }
                withContext(NonCancellable + Dispatchers.IO) { project.dispose() }
                instrumentation.runOnMainSync { view.release() }
            }
        }
    }
    @Test(timeout = 10000) fun connectionCloseStateTracksOwnedProcessAndRejectsRestart() {
        val context = instrumentation.targetContext
        fun connection() = UbuntuLspConnection(
            RuntimeCommand("/system/bin/sh", listOf("-c", "exec sleep 30"), mapOf("PATH" to "/system/bin")),
            context.cacheDir, {}, LspWireAdapter("/workspace/fixture", "/workspace/fixture/main.py", "python"))
        val neverStarted = connection()
        assertFalse(neverStarted.isClosed)
        neverStarted.close()
        assertTrue(neverStarted.isClosed)
        assertTrue(runCatching { neverStarted.start() }.isFailure)
        val active = connection()
        try {
            active.start()
            assertTrue(active.isAlive)
            assertFalse(active.isClosed)
        } finally { active.close() }
        assertTrue(active.isClosed)
        assertFalse(active.isAlive)
        active.close()
    }

}
