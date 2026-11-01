package dev.zakocoder.app.editor.lsp

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val LSP_MAX_FRAME_BYTES = 8 * 1024 * 1024
private const val MAX_HEADER_BYTES = 8192

/** A narrow compatibility boundary for Sora 0.23.6's raw file URIs and extension IDs. */
internal class LspWireAdapter(
    projectPath: String,
    documentPath: String,
    private val languageId: String,
    private val documentSnapshot: (() -> String)? = null,
    private val beforeDiagnostics: (String) -> Unit = {},
) {
    @Volatile private var sentVersion: Int = -1
    @Volatile private var sentText: String? = null
    internal fun hasSentSnapshot(text: String): Boolean = sentText == text
    private val visibleDocument = documentPath
    private val uriMap = listOf(projectPath, documentPath).associate { path ->
        "file://$path" to URI("file", "", path, null).toASCIIString()
    }
    private fun uri(value: String): String = uriMap[value] ?: value
    private fun normalize(objectValue: JSONObject, key: String) {
        val value = objectValue.opt(key)
        if (value is String) objectValue.put(key, uri(value))
    }
    private fun folders(values: JSONArray?, requireNames: Boolean = false) {
        if (values != null) for (index in 0 until values.length()) values.optJSONObject(index)?.let {
            normalize(it, "uri")
            if (requireNames && it.optString("name").isBlank()) {
                val path = runCatching { URI(it.optString("uri")).path }.getOrNull().orEmpty()
                it.put("name", path.trimEnd('/').substringAfterLast('/').ifBlank { "workspace" })
            }
        }
    }
    fun outgoing(message: JSONObject): JSONObject {
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: return message
        when {
            method == "initialize" -> {
                normalize(params, "rootUri"); folders(params.optJSONArray("workspaceFolders"), requireNames = true)
                // Sora 0.23.6 renders basic pushed diagnostics but omits this capability.
                // TypeScript language server 4.4.0 disables all diagnostics without it.
                val capabilities = params.optJSONObject("capabilities") ?: JSONObject().also { params.put("capabilities", it) }
                val document = capabilities.optJSONObject("textDocument") ?: JSONObject().also { capabilities.put("textDocument", it) }
                if (!document.has("publishDiagnostics")) document.put("publishDiagnostics", JSONObject())
                // Sora sends codeActionLiteralSupport={} without the required codeActionKind.
                // We expose no code-action UI, so omit the unsupported capability entirely
                // instead of inventing action kinds to satisfy strict servers.
                document.remove("codeAction")
                document.put("diagnostic", JSONObject().put("dynamicRegistration", false).put("relatedDocumentSupport", false))
            }
            method.startsWith("textDocument/") -> params.optJSONObject("textDocument")?.let { document ->
                normalize(document, "uri")
                if (method == "textDocument/didOpen") {
                    document.put("languageId", languageId)
                    sentText = document.optString("text")
                    sentVersion = document.optInt("version", -1)
                } else if (method == "textDocument/didChange") {
                    sentVersion = document.optInt("version", -1)
                    val changes = params.optJSONArray("contentChanges")
                    val full = changes?.optJSONObject((changes.length() - 1).coerceAtLeast(0))
                    if (full != null && !full.has("range")) sentText = full.optString("text")
                }
            }
            method == "workspace/didChangeWorkspaceFolders" -> params.optJSONObject("event")?.let { event -> folders(event.optJSONArray("added"), requireNames = true); folders(event.optJSONArray("removed"), requireNames = true) }
            method == "workspace/didChangeWatchedFiles" -> folders(params.optJSONArray("changes"))
        }
        return message
    }
    fun incoming(message: JSONObject): JSONObject? {
        if (message.optString("method") != "textDocument/publishDiagnostics") return message
        val params = message.optJSONObject("params") ?: throw IOException("Invalid diagnostics parameters")
        val diagnostics = params.optJSONArray("diagnostics") ?: throw IOException("Invalid diagnostics list")
        if (diagnostics.length() > 10000) throw IOException("Diagnostics limit exceeded")
        val documentUri = params.opt("uri") as? String ?: throw IOException("Missing diagnostic URI")
        val parsed = try { URI(documentUri) } catch (_: Exception) { throw IOException("Invalid diagnostic URI") }
        if (parsed.scheme != "file" || parsed.rawAuthority?.isNotEmpty() == true || parsed.query != null || parsed.fragment != null) throw IOException("Invalid diagnostic URI")
        if (parsed.path == visibleDocument) {
            // A late response must neither clear newer diagnostics nor address old line indices.
            if (params.has("version") && params.optInt("version", -1) < sentVersion) return null
            val current = documentSnapshot?.invoke()
            if (current != null) {
                if (sentText != null && current != sentText) return null
                val lines = current.split('\n')
                val valid = JSONArray()
                for (index in 0 until diagnostics.length()) {
                    val diagnostic = diagnostics.optJSONObject(index) ?: continue
                    val range = diagnostic.optJSONObject("range") ?: continue
                    fun position(key: String): Pair<Int, Int>? {
                        val point = range.optJSONObject(key) ?: return null
                        val line = point.optInt("line", -1); val column = point.optInt("character", -1)
                        return if (line in lines.indices && column in 0..lines[line].length) line to column else null
                    }
                    val start = position("start") ?: continue
                    val end = position("end") ?: continue
                    if (end.first > start.first || end.first == start.first && end.second >= start.second) valid.put(diagnostic)
                }
                params.put("diagnostics", valid)
            }
        }
        // The upstream client adds diagnostics instead of replacing. Clear only this explicitly
        // named document, including an empty server array, immediately before its handler runs.
        beforeDiagnostics(parsed.path ?: throw IOException("Missing diagnostic path"))
        return message
    }
}

private fun decodeJson(bytes: ByteArray): JSONObject = try {
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val tokener = JSONTokener(decoder.decode(ByteBuffer.wrap(bytes)).toString())
    val message = tokener.nextValue() as? JSONObject ?: throw IOException("Invalid JSON-RPC object")
    if (tokener.nextClean() != '\u0000') throw IOException("Trailing JSON-RPC data")
    message
} catch (_: Exception) { throw IOException("Invalid UTF-8 JSON-RPC frame") }

private fun readLength(header: ByteArray): Int {
    val lines = header.toString(Charsets.US_ASCII).removeSuffix("\r\n\r\n").split("\r\n")
    var length: Int? = null
    for (line in lines) {
        val colon = line.indexOf(':')
        if (colon <= 0) throw IOException("Invalid JSON-RPC header")
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (!name.matches(Regex("[A-Za-z0-9-]+")) || value.any { it.isISOControl() && it != '\t' }) throw IOException("Invalid JSON-RPC header")
        when {
            name.equals("Content-Length", true) -> {
                if (length != null || !value.matches(Regex("[0-9]{1,8}"))) throw IOException("Invalid JSON-RPC content length")
                length = value.toIntOrNull()?.takeIf { it in 1..LSP_MAX_FRAME_BYTES } ?: throw IOException("JSON-RPC frame limit exceeded")
            }
            name.equals("Content-Type", true) -> {
                val charset = Regex("charset\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
                if (charset != null && charset.lowercase() !in setOf("utf-8", "utf8")) throw IOException("Unsupported JSON-RPC charset")
            }
        }
    }
    return length ?: throw IOException("Missing JSON-RPC content length")
}
private fun encodeFrame(message: JSONObject): ByteArray {
    val body = message.toString().toByteArray(Charsets.UTF_8)
    if (body.size > LSP_MAX_FRAME_BYTES) throw IOException("JSON-RPC frame limit exceeded")
    return "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + body
}

/** Handles arbitrary header/body chunk boundaries; lengths always count UTF-8 bytes. */
internal class LspFrameOutput(private val target: OutputStream, private val transform: (JSONObject) -> JSONObject) : OutputStream() {
    private val header = ByteArrayOutputStream()
    private var length: Int? = null
    private val body = ByteArrayOutputStream()
    @Synchronized override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    @Synchronized override fun write(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        var cursor = offset
        val end = offset + count
        while (cursor < end) {
            val expected = length
            if (expected == null) {
                val byte = bytes[cursor++]
                if (byte.toInt() !in 0..127) throw IOException("Non-ASCII JSON-RPC header")
                header.write(byte.toInt())
                if (header.size() > MAX_HEADER_BYTES) throw IOException("JSON-RPC header limit exceeded")
                val value = header.toByteArray()
                if (value.size >= 4 && value.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) length = readLength(value)
            } else {
                val size = minOf(end - cursor, expected - body.size())
                body.write(bytes, cursor, size); cursor += size
                if (body.size() == expected) {
                    target.write(encodeFrame(transform(decodeJson(body.toByteArray()))))
                    header.reset(); body.reset(); length = null
                }
            }
        }
    }
    @Synchronized override fun flush() = target.flush()
    @Synchronized override fun close() {
        try { if (header.size() != 0 || body.size() != 0) throw IOException("Truncated JSON-RPC frame") }
        finally { target.close() }
    }
}

internal class LspFrameInput(private val source: InputStream, private val transform: (JSONObject) -> JSONObject?) : InputStream() {
    private var frame = ByteArrayInputStream(ByteArray(0))
    private fun next(): Boolean {
        val header = ByteArrayOutputStream()
        while (true) {
            val byte = source.read()
            if (byte < 0) {
                if (header.size() == 0) return false
                throw IOException("Truncated JSON-RPC header")
            }
            if (byte > 127) throw IOException("Non-ASCII JSON-RPC header")
            header.write(byte)
            if (header.size() > MAX_HEADER_BYTES) throw IOException("JSON-RPC header limit exceeded")
            val bytes = header.toByteArray()
            if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) {
                val size = readLength(bytes)
                val body = ByteArray(size)
                var cursor = 0
                while (cursor < size) {
                    val count = source.read(body, cursor, size - cursor)
                    if (count < 0) throw IOException("Truncated JSON-RPC body")
                    if (count == 0) throw IOException("Invalid zero-byte JSON-RPC read")
                    cursor += count
                }
                val transformed = transform(decodeJson(body))
                if (transformed != null) {
                    frame = ByteArrayInputStream(encodeFrame(transformed))
                    return true
                }
                header.reset()

            }
        }
    }
    override fun read(): Int { if (frame.available() == 0 && !next()) return -1; return frame.read() }
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        if (count == 0) return 0
        if (frame.available() == 0 && !next()) return -1
        return frame.read(bytes, offset, count)
    }
    override fun close() = source.close()
}
