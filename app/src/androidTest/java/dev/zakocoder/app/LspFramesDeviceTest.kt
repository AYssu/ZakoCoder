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
        assertEquals("file://$path", wire.incoming(response)!!.getJSONObject("result").getString("uri"))
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

    @Test fun initializeAdvertisesPushAndPullDiagnosticsWithoutDynamicOrRelatedDocumentSupport() {
        val wire = LspWireAdapter(root, path, "typescript")
        val initialize = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            .put("params", JSONObject().put("rootUri", "file://$root").put("capabilities", JSONObject()
                .put("textDocument", JSONObject().put("completion", JSONObject().put("dynamicRegistration", false)))))
        val document = wire.outgoing(initialize).getJSONObject("params").getJSONObject("capabilities").getJSONObject("textDocument")
        assertTrue(document.has("publishDiagnostics"))
        assertEquals(0, document.getJSONObject("publishDiagnostics").length())
        assertFalse(document.getJSONObject("completion").getBoolean("dynamicRegistration"))
        val pull = document.getJSONObject("diagnostic")
        assertEquals(2, pull.length())
        assertFalse(pull.getBoolean("dynamicRegistration"))
        assertFalse(pull.getBoolean("relatedDocumentSupport"))
        // Unsupported upstream flags cannot override the implementation's narrower support.
        document.put("diagnostic", JSONObject().put("dynamicRegistration", true).put("relatedDocumentSupport", true))
        val normalizedPull = wire.outgoing(initialize).getJSONObject("params").getJSONObject("capabilities")
            .getJSONObject("textDocument").getJSONObject("diagnostic")
        assertEquals(2, normalizedPull.length())
        assertFalse(normalizedPull.getBoolean("dynamicRegistration"))
        assertFalse(normalizedPull.getBoolean("relatedDocumentSupport"))
        val existing = JSONObject().put("relatedInformation", false)
        document.put("publishDiagnostics", existing)
        assertEquals(false, wire.outgoing(initialize).getJSONObject("params").getJSONObject("capabilities")
            .getJSONObject("textDocument").getJSONObject("publishDiagnostics").getBoolean("relatedInformation"))
    }

    @Test fun initializeRemovesUnsupportedCodeActionsFromSoraCapabilityShape() {
        // Captured Sora 0.23.6 initialize shape: literal support has no codeActionKind.
        // Strict Dart/Kotlin servers reject this payload before initialize can complete.
        val params = JSONObject("""{"capabilities":{"textDocument":{
            "codeAction":{"dynamicRegistration":false,"codeActionLiteralSupport":{}},
            "completion":{"dynamicRegistration":false},
            "hover":{"dynamicRegistration":false},
            "publishDiagnostics":{"relatedInformation":false}
        }}}""")
        val document = params.getJSONObject("capabilities").getJSONObject("textDocument")
        assertFalse(document.getJSONObject("codeAction").getJSONObject("codeActionLiteralSupport").has("codeActionKind"))
        val completion = document.getJSONObject("completion").toString()
        val hover = document.getJSONObject("hover").toString()
        val diagnostics = document.getJSONObject("publishDiagnostics").toString()
        val initialize = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").put("params", params)
        val wire = LspWireAdapter(root, path, "dart")
        val output = ByteArrayOutputStream()
        LspFrameOutput(output, wire::outgoing).use { it.write(frame(initialize)) }
        val actual = message(output.toByteArray()).getJSONObject("params").getJSONObject("capabilities").getJSONObject("textDocument")
        assertFalse("Code actions have no supported UI and must not be advertised", actual.has("codeAction"))
        assertEquals(completion, actual.getJSONObject("completion").toString())
        assertEquals(hover, actual.getJSONObject("hover").toString())
        assertEquals(diagnostics, actual.getJSONObject("publishDiagnostics").toString())
    }

    private fun diagnostic(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, text: String = "fixture"): JSONObject =
        JSONObject().put("message", text).put("range", JSONObject()
            .put("start", JSONObject().put("line", startLine).put("character", startColumn))
            .put("end", JSONObject().put("line", endLine).put("character", endColumn)))

    private fun diagnosticNotification(version: Int?, values: JSONArray): JSONObject {
        val params = JSONObject().put("uri", URI("file", "", path, null).toASCIIString()).put("diagnostics", values)
        if (version != null) params.put("version", version)
        return JSONObject().put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics").put("params", params)
    }

    private fun fullChange(version: Int, text: String): JSONObject = JSONObject().put("jsonrpc", "2.0")
        .put("method", "textDocument/didChange").put("params", JSONObject()
            .put("textDocument", JSONObject().put("uri", "file://$path").put("version", version))
            .put("contentChanges", JSONArray().put(JSONObject().put("text", text))))

    @Test fun staleDiagnosticVersionsDoNotClearNewerResultsAndInputContinuesToNextFrame() {
        var cleared = 0
        var current = "latest\n"
        val wire = LspWireAdapter(root, path, "python", documentSnapshot = { current }) { cleared++ }
        wire.outgoing(open("initial\n"))
        wire.outgoing(fullChange(2, current))
        val stale = diagnosticNotification(1, JSONArray().put(diagnostic(0, 0, 0, 1, "old")))
        assertNull(wire.incoming(stale))
        assertEquals("Discarding old diagnostics must preserve the displayed new results", 0, cleared)
        val response = JSONObject().put("jsonrpc", "2.0").put("id", 91).put("result", "following response")
        val source = LspFrameInput(ByteArrayInputStream(frame(stale) + frame(response)), wire::incoming)
        val forwarded = source.use { it.readBytes() }
        assertEquals(91, message(forwarded).getInt("id"))
        assertEquals("following response", message(forwarded).getString("result"))
        assertEquals(1, Regex("Content-Length:").findAll(forwarded.toString(Charsets.UTF_8)).count())
        assertEquals(0, cleared)
        // An old empty publication must not remove currently valid diagnostics either.
        assertNull(wire.incoming(diagnosticNotification(1, JSONArray())))
        assertEquals(0, cleared)
        assertNotNull(wire.incoming(diagnosticNotification(2, JSONArray().put(diagnostic(0, 0, 0, 1, "latest")))))
        assertEquals(1, cleared)
    }

    @Test fun editingBeforeDidChangeDropsDiagnosticsEvenWhenServerVersionMatchesLastSent() {
        var cleared = 0
        var current = "original\n"
        val wire = LspWireAdapter(root, path, "python", documentSnapshot = { current }) { cleared++ }
        wire.outgoing(open(current))
        current = "new\n"
        val late = diagnosticNotification(1, JSONArray().put(diagnostic(0, 0, 0, 1)))
        assertNull("UI edit must invalidate diagnostics before debounce sends didChange", wire.incoming(late))
        assertNull("Unversioned diagnostics must respect the unsent UI snapshot", wire.incoming(diagnosticNotification(null, JSONArray())))
        assertEquals(0, cleared)
        wire.outgoing(fullChange(2, current))
        assertNotNull(wire.incoming(diagnosticNotification(2, JSONArray().put(diagnostic(0, 0, 0, 1)))))
        assertEquals(1, cleared)
    }

    @Test fun outOfBoundsDiagnosticCoordinatesAreFilteredWhileValidAndEmptyUpdatesStillReplace() {
        var cleared = 0
        val current = "🙂x\nok"
        val wire = LspWireAdapter(root, path, "python", documentSnapshot = { current }) { cleared++ }
        wire.outgoing(open(current))
        val values = JSONArray()
            .put(diagnostic(0, 2, 0, 3, "UTF16 x"))
            .put(diagnostic(0, 3, 1, 2, "valid multiline"))
            .put(diagnostic(99, 0, 99, 1, "missing line"))
            .put(diagnostic(-1, 0, 0, 1, "negative line"))
            .put(diagnostic(0, -1, 0, 1, "negative column"))
            .put(diagnostic(0, 0, 0, 4, "past line end"))
            .put(diagnostic(1, 1, 0, 1, "reversed lines"))
            .put(diagnostic(0, 2, 0, 1, "reversed columns"))
            .put(JSONObject().put("message", "missing range"))
            .put("not a diagnostic")
        val accepted = requireNotNull(wire.incoming(diagnosticNotification(1, values)))
            .getJSONObject("params").getJSONArray("diagnostics")
        assertEquals(2, accepted.length())
        assertEquals("UTF16 x", accepted.getJSONObject(0).getString("message"))
        assertEquals("valid multiline", accepted.getJSONObject(1).getString("message"))
        assertEquals(1, cleared)
        val empty = requireNotNull(wire.incoming(diagnosticNotification(1, JSONArray())))
        assertEquals(0, empty.getJSONObject("params").getJSONArray("diagnostics").length())
        assertEquals("A current empty update must still clear prior diagnostics", 2, cleared)
    }

    @Test fun initializeWorkspaceFoldersReceiveRequiredNamesAndPreserveExplicitNames() {
        val project = "/workspace/测试 # 项目"
        val encoded = URI("file", "", project, null).toASCIIString()
        val wire = LspWireAdapter(project, "$project/Main.kt", "kotlin")
        val folders = JSONArray()
            .put(JSONObject().put("uri", "file://$project"))
            .put(JSONObject().put("uri", encoded).put("name", "   "))
            .put(JSONObject().put("uri", encoded).put("name", "User workspace name"))
            .put(JSONObject().put("uri", "file:///").put("name", ""))
        val initialize = JSONObject().put("method", "initialize").put("params", JSONObject()
            .put("rootUri", "file://$project").put("workspaceFolders", folders))
        val params = wire.outgoing(initialize).getJSONObject("params")
        assertEquals(encoded, params.getString("rootUri"))
        val actual = params.getJSONArray("workspaceFolders")
        assertEquals(encoded, actual.getJSONObject(0).getString("uri"))
        assertEquals("测试 # 项目", actual.getJSONObject(0).getString("name"))
        assertEquals("测试 # 项目", actual.getJSONObject(1).getString("name"))
        assertEquals("User workspace name", actual.getJSONObject(2).getString("name"))
        assertEquals("workspace", actual.getJSONObject(3).getString("name"))
    }

    @Test fun workspaceFolderChangeNormalizesNamesForAddedAndRemovedButNotWatchedFiles() {
        val project = "/workspace/测试 # 项目"
        val encoded = URI("file", "", project, null).toASCIIString()
        val wire = LspWireAdapter(project, "$project/Main.kt", "kotlin")
        val change = JSONObject().put("method", "workspace/didChangeWorkspaceFolders")
            .put("params", JSONObject().put("event", JSONObject()
                .put("added", JSONArray().put(JSONObject().put("uri", "file://$project")))
                .put("removed", JSONArray().put(JSONObject().put("uri", encoded).put("name", "")))))
        val event = wire.outgoing(change).getJSONObject("params").getJSONObject("event")
        for (key in listOf("added", "removed")) {
            val folder = event.getJSONArray(key).getJSONObject(0)
            assertEquals(encoded, folder.getString("uri"))
            assertEquals("测试 # 项目", folder.getString("name"))
        }
        val watched = JSONObject().put("method", "workspace/didChangeWatchedFiles").put("params", JSONObject()
            .put("changes", JSONArray().put(JSONObject().put("uri", "file://$project").put("type", 2))))
        val file = wire.outgoing(watched).getJSONObject("params").getJSONArray("changes").getJSONObject(0)
        assertEquals(encoded, file.getString("uri"))
        assertEquals(2, file.getInt("type"))
        assertFalse("File change records have no WorkspaceFolder name field", file.has("name"))
    }

}
