package dev.zakocoder.app.editor.lsp

import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticProvider
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.lsp4j.DiagnosticSeverity

/** Sora 0.24.7 registers its diagnostic provider only when inlay hints are enabled. */
internal fun attachLspDiagnostics(view: CodeEditor, document: LspEditor): () -> Unit {
    val provider = DiagnosticProvider { container ->
        val text = view.text
        document.diagnostics.forEachIndexed { index, diagnostic ->
            val start = diagnostic.range?.start ?: return@forEachIndexed
            val end = diagnostic.range?.end ?: return@forEachIndexed
            if (start.line !in 0 until text.lineCount || end.line !in 0 until text.lineCount ||
                start.character !in 0..text.getColumnCount(start.line) ||
                end.character !in 0..text.getColumnCount(end.line)) return@forEachIndexed
            val from = text.getCharIndex(start.line, start.character)
            val to = text.getCharIndex(end.line, end.character)
            if (to < from) return@forEachIndexed
            val severity = when (diagnostic.severity) {
                DiagnosticSeverity.Error -> DiagnosticRegion.SEVERITY_ERROR
                DiagnosticSeverity.Warning -> DiagnosticRegion.SEVERITY_WARNING
                else -> DiagnosticRegion.SEVERITY_TYPO
            }
            val message = diagnostic.message?.let { if (it.isLeft) it.left else it.right.value }.orEmpty()
            container.addDiagnostic(DiagnosticRegion(from, to, severity, index.toLong(),
                DiagnosticDetail(diagnostic.severity?.name.orEmpty(), message, extraData = diagnostic)))
        }
    }
    view.registerDiagnosticProvider(provider)
    return {
        view.unregisterDiagnosticProvider(provider)
        view.invalidateDiagnostics()
    }
}
