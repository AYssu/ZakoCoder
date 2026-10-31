package dev.zakocoder.app

import dev.zakocoder.app.editor.lsp.*
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.utils.FileUri
import org.eclipse.lsp4j.Diagnostic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

/** Protocol regressions only; no environment, server installation, secrets, or network. */
class LspFramesDeviceTest {
    private val root = "/workspace/9516372b-a8da-4a82-b2b7-9a5acb1cc52c"
    private val path = "$root/src/测试 # %20 file.py"
    private fun frame(value: JSONObject): ByteArray { val body = value.toString().toByteArray(); return "Content-Length: ${body.size}\r\n\r\n".toByteArray() + body }
    private fun message(bytes: ByteArray): JSONObject { val text = bytes.toString(Charsets.UTF_8); return JSONObject(text.substringAfter("\r\n\r\n")) }
    private fun open(text: String = "包含🙂"): JSONObject = JSONObject().put("jsonrpc", "2.0").put("method", "textDocument/didOpen").put("params",
        JSONObject().put("textDocument", JSONObject().put("uri", "file://$path").put("languageId", "py").put("version", 1).put("text", text)))

    @Test fun outgoingOneByteChunksEncodeOnlyDocumentUriAndCorrectLanguageId() {
        val output = ByteArrayOutputStream()
        val wire = LspWireAdapter(root, path, "python")
        val target = LspFrameOutput(output, wire::outgoing)
        val untouched = "source contains file://$path 包含🙂"
        frame(open(untouched)).forEach { target.write(it.toInt() and 255) }; target.flush()
        val result = message(output.toByteArray()).getJSONObject("params").getJSONObject("textDocument")
        assertEquals(URI("file", "", path, null).toASCIIString(), result.getString("uri"))
        assertEquals("python", result.getString("languageId")); assertEquals(untouched, result.getString("text"))
        val full = output.toByteArray(); val header = full.toString(Charsets.UTF_8).substringBefore("\r\n\r\n")
        val byteLength = header.substringAfter(": ").toInt()
        assertEquals(byteLength, full.size - (header.toByteArray().size + 4))
    }
    @Test fun arbitraryInputChunkSizesPreserveUtf8AndMultipleFrames() {
        val first = open("中文🙂"); val second = JSONObject().put("jsonrpc", "2.0").put("id", 7).put("result", "回覆")
        val output = ByteArrayOutputStream()
        val target = LspFrameOutput(output) { it }
        val bytes = frame(first) + frame(second)
        var cursor = 0
        while (cursor < bytes.size) { val count = minOf(7, bytes.size - cursor); target.write(bytes, cursor, count); cursor += count }
        val source = LspFrameInput(ByteArrayInputStream(output.toByteArray())) { it }
        val observed = source.readBytes().toString(Charsets.UTF_8)
        assertTrue(observed.contains("中文🙂")); assertTrue(observed.contains("回覆")); assertEquals(2, Regex("Content-Length:").findAll(observed).count())
    }
    @Test fun literalPercentNamesAreNotDoubleDecodedAndNormalUriAlreadyEncodedIsStable() {
        val wire = LspWireAdapter(root, path, "python")
        val expected = URI("file", "", path, null).toASCIIString()
        val value = open(); value.getJSONObject("params").getJSONObject("textDocument").put("uri", expected)
        assertEquals(expected, wire.outgoing(value).getJSONObject("params").getJSONObject("textDocument").getString("uri"))
        assertTrue(expected.contains("%2520")); assertTrue(expected.contains("%23"))
    }
    @Test fun emptyPublishDiagnosticsReplacesPreviouslyReportedErrorsBeforeUpstreamAdds() {
        val project = LspProject(root)
        val key = FileUri(path)
        project.diagnosticsContainer.setDiagnostics(key, mutableListOf(Diagnostic()))
        var callbacks = 0
        val wire = LspWireAdapter(root, path, "python") { incomingPath ->
            assertEquals(path, incomingPath); callbacks++
            project.diagnosticsContainer.setDiagnostics(FileUri(incomingPath), mutableListOf())
        }
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics").put("params", JSONObject()
            .put("uri", URI("file", "", path, null).toASCIIString()).put("diagnostics", JSONArray()))
        LspFrameInput(ByteArrayInputStream(frame(notification)), wire::incoming).readBytes()
        assertEquals(1, callbacks); assertTrue(project.diagnosticsContainer.getDiagnostics(key).isEmpty())
    }
    @Test fun applicationArgumentsAndResponsesAreNotRecursivelyRewritten() {
        val wire = LspWireAdapter(root, path, "python")
        val request = JSONObject().put("method", "workspace/executeCommand").put("params", JSONObject().put("arguments", JSONArray().put(JSONObject().put("uri", "file://$path"))))
        assertEquals("file://$path", wire.outgoing(request).getJSONObject("params").getJSONArray("arguments").getJSONObject(0).getString("uri"))
        val response = JSONObject().put("id", 1).put("result", JSONObject().put("uri", "file://$path"))
        assertEquals("file://$path", wire.incoming(response).getJSONObject("result").getString("uri"))
    }
    @Test fun rejectsDuplicateOversizedTruncatedAndNonUtf8Frames() {
        val badFrames = listOf(
            "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}".toByteArray(),
            "Content-Length: 8388609\r\n\r\n".toByteArray(),
            "Content-Length: 4\r\n\r\n{}".toByteArray(),
            "Content-Length: 2\r\n".toByteArray(),
            "Content-Length: 2\r\nContent-Type: application/vscode-jsonrpc; charset=utf-16\r\n\r\n{}".toByteArray(),
            "Content-Length: 2\r\n\r\n".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28),
            "Content-Length: 3\r\n\r\n{}x".toByteArray(),
        )
        for ((index, bytes) in badFrames.withIndex()) assertTrue("bad frame $index", runCatching { LspFrameInput(ByteArrayInputStream(bytes)) { it }.readBytes() }.isFailure)
    }
    @Test fun rejectsOversizedHeadersAndIncompleteOutputOnClose() {
        assertTrue(runCatching { LspFrameInput(ByteArrayInputStream(("X: " + "x".repeat(9000)).toByteArray())) { it }.readBytes() }.isFailure)
        val target = LspFrameOutput(ByteArrayOutputStream()) { it }
        target.write("Content-Length: 10\r\n\r\n{}".toByteArray())
        assertTrue(runCatching { target.close() }.isFailure)
    }
    @Test fun actualSoraClientHandlesConsecutiveDiagnosticsBeforeReadingNextNotification() {
        val project = LspProject(root)
        val key = FileUri(path)
        val observed = mutableListOf<Int>()
        val threads = mutableListOf<Long>()
        val wire = LspWireAdapter(root, path, "python") { incomingPath ->
            threads += Thread.currentThread().id
            project.diagnosticsContainer.setDiagnostics(FileUri(incomingPath), mutableListOf())
        }
        val lspProject = project
        val clientContext = object : io.github.rosemoe.sora.lsp.client.ClientContext {
            override fun getEditor(documentUri: FileUri): io.github.rosemoe.sora.lsp.editor.LspEditor? = null
            override val projectPath = FileUri(root)
            override val project = lspProject
            override val requestManager: io.github.rosemoe.sora.lsp.client.languageserver.requestmanager.RequestManager? = null
            override val eventListener = io.github.rosemoe.sora.lsp.client.languageserver.wrapper.EventHandler.EventListener.DEFAULT
        }
        val client = object : io.github.rosemoe.sora.lsp.client.DefaultLanguageClient(clientContext) {
            override fun publishDiagnostics(params: org.eclipse.lsp4j.PublishDiagnosticsParams) {
                super.publishDiagnostics(params)
                observed += project.diagnosticsContainer.getDiagnostics(key).size
                threads += Thread.currentThread().id
            }
        }
        fun diagnostics(count: Int): JSONObject {
            val values = JSONArray()
            repeat(count) { index -> values.put(JSONObject().put("range", JSONObject()
                .put("start", JSONObject().put("line", index).put("character", 0))
                .put("end", JSONObject().put("line", index).put("character", 1)))
                .put("message", "fixture diagnostic $index")) }
            return JSONObject().put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics").put("params", JSONObject()
                .put("uri", URI("file", "", path, null).toASCIIString()).put("diagnostics", values))
        }
        val bytes = listOf(1, 0, 2, 0).map { frame(diagnostics(it)) }.fold(ByteArray(0)) { all, next -> all + next }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val launcher = org.eclipse.lsp4j.launch.LSPLauncher.createClientLauncher(client,
                LspFrameInput(ByteArrayInputStream(bytes), wire::incoming), ByteArrayOutputStream(), executor,
                java.util.function.Function<org.eclipse.lsp4j.jsonrpc.MessageConsumer, org.eclipse.lsp4j.jsonrpc.MessageConsumer> { it })
            launcher.startListening().get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf(1, 0, 2, 0), observed)
            assertEquals("Before-replace and real upstream handlers execute on the same reader", 1, threads.toSet().size)
            assertTrue(project.diagnosticsContainer.getDiagnostics(key).isEmpty())
        } finally { executor.shutdownNow() }
    }

    @Test fun initializeAdvertisesActualBasicPushDiagnosticsWithoutInventingAdvancedSupport() {
        val wire = LspWireAdapter(root, path, "typescript")
        val initialize = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            .put("params", JSONObject().put("rootUri", "file://$root").put("capabilities", JSONObject()
                .put("textDocument", JSONObject().put("completion", JSONObject().put("dynamicRegistration", false)))))
        val document = wire.outgoing(initialize).getJSONObject("params").getJSONObject("capabilities").getJSONObject("textDocument")
        assertTrue(document.has("publishDiagnostics"))
        assertEquals(0, document.getJSONObject("publishDiagnostics").length())
        assertFalse(document.getJSONObject("completion").getBoolean("dynamicRegistration"))
        val existing = JSONObject().put("relatedInformation", false)
        document.put("publishDiagnostics", existing)
        assertEquals(false, wire.outgoing(initialize).getJSONObject("params").getJSONObject("capabilities")
            .getJSONObject("textDocument").getJSONObject("publishDiagnostics").getBoolean("relatedInformation"))
    }

}
