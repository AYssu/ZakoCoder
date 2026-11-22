package dev.zakocoder.app.ai

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Original-byte attachment archive, independent of the sender and workspace source files. */
class AiFileAttachmentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.noBackupFilesDir, "ai-files-v1")

    suspend fun import(uri: Uri, onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> }): AiFileAttachment = guarded {
        if (uri.scheme != "content") failure(AiFileAttachmentException.Reason.Unsupported)
        archiveLock.withLock {
            ensureDirectory()
            var rawName: String? = null
            var declaredBytes: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) rawName = cursor.getString(nameColumn)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredBytes = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                }
            }
            if ((declaredBytes ?: 0) > AiFileAttachments.MAX_FILE_BYTES) failure(AiFileAttachmentException.Reason.TooLarge)
            val usage = usage()
            if (usage.first >= AiFileAttachments.MAX_STORED_FILES || usage.second + (declaredBytes ?: 0) > AiFileAttachments.MAX_STORED_BYTES)
                failure(AiFileAttachmentException.Reason.Capacity)
            val id = UUID.randomUUID().toString()
            val temporary = File(directory, "$id.part")
            val destination = File(directory, "$id.bin")
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                val fd = Os.open(temporary.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
                try {
                    FileOutputStream(fd).use { output ->
                        (resolver.openInputStream(uri) ?: failure(AiFileAttachmentException.Reason.Unavailable)).use { input ->
                            val buffer = ByteArray(32 * 1024)
                            onProgress(0, declaredBytes)
                            var progressAt = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val allowed = minOf(buffer.size.toLong(), AiFileAttachments.MAX_FILE_BYTES + 1 - copied).toInt()
                                val count = input.read(buffer, 0, allowed)
                                if (count < 0) break
                                if (count == 0) failure(AiFileAttachmentException.Reason.Unavailable)
                                copied += count
                                if (copied > AiFileAttachments.MAX_FILE_BYTES) failure(AiFileAttachmentException.Reason.TooLarge)
                                if (usage.second + copied > AiFileAttachments.MAX_STORED_BYTES) failure(AiFileAttachmentException.Reason.Capacity)
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                if (copied - progressAt >= 256 * 1024) { onProgress(copied, declaredBytes); progressAt = copied }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                } finally { Os.close(fd) }
                currentCoroutineContext().ensureActive()
                val attachment = AiFileAttachment(id, safeName(rawName), safeMime(resolver.getType(uri)), copied,
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                onProgress(copied, declaredBytes)
                currentCoroutineContext().ensureActive()
                ensureDirectory()
                if (exists(destination) || !temporary.renameTo(destination)) failure(AiFileAttachmentException.Reason.Unavailable)
                committed = true
                attachment
            } finally {
                if (!committed) deleteRegular(temporary)
            }
        }
    }

    /** Character offsets are UTF-16 positions excluding a leading BOM, with intact surrogate pairs. */
    suspend fun read(attachment: AiFileAttachment, offset: Int = 0, limit: Int = 12000): JSONObject = guarded {
        if (offset < 0 || offset > AiFileAttachments.MAX_FILE_BYTES || limit !in 1..12000)
            failure(AiFileAttachmentException.Reason.Unsupported)
        archiveLock.withLock {
            textReader(attachment).use { reader ->
                var position = 0
                var first = true
                suspend fun next(): String? {
                    currentCoroutineContext().ensureActive()
                    var value = reader.read()
                    if (first) { first = false; if (value == 0xFEFF) value = reader.read() }
                    if (value < 0) return null
                    val char = value.toChar()
                    validateTextChar(char)
                    if (char.isHighSurrogate()) {
                        val low = reader.read()
                        if (low < 0 || !low.toChar().isLowSurrogate()) failure(AiFileAttachmentException.Reason.Unsupported)
                        return "$char${low.toChar()}"
                    }
                    if (char.isLowSurrogate()) failure(AiFileAttachmentException.Reason.Unsupported)
                    return char.toString()
                }
                while (position < offset) {
                    val token = next() ?: break
                    position += token.length
                    if (position > offset) failure(AiFileAttachmentException.Reason.Unsupported)
                }
                val content = StringBuilder()
                var more = false
                while (true) {
                    val token = next() ?: break
                    if (content.length + token.length > maxOf(2, limit)) { more = true; break }
                    content.append(token)
                    position += token.length
                }
                JSONObject().put("id", attachment.id).put("bytes", attachment.bytes).put("offset", minOf(offset, position))
                    .put("content", content.toString()).put("next_offset", position).put("has_more", more)
            }
        }
    }

    /** Literal streaming search, with bounded snippets even for a single enormous line. */
    suspend fun search(attachment: AiFileAttachment, query: String, limit: Int = 30, caseSensitive: Boolean = true): JSONObject = guarded {
        if (query.isEmpty() || query.length > 256 || query.any { it == '\n' || it == '\r' } || limit !in 1..100)
            failure(AiFileAttachmentException.Reason.Unsupported)
        validateUnicodeQuery(query)
        archiveLock.withLock {
            val needle = if (caseSensitive) query else query.map(Char::lowercaseChar).joinToString("")
            val prefix = IntArray(needle.length)
            for (index in 1 until needle.length) {
                var candidate = prefix[index - 1]
                while (candidate > 0 && needle[index] != needle[candidate]) candidate = prefix[candidate - 1]
                if (needle[index] == needle[candidate]) candidate++
                prefix[index] = candidate
            }
            val matches = JSONArray()
            var truncated = false
            textReader(attachment).use { reader ->
                var line = 1
                var offset = 0
                var column = 0L
                var matched = 0
                var first = true
                val snippet = CharArray(240)
                var snippetSize = 0
                var snippetStart = 0
                val buffer = CharArray(8192)
                loop@ while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (first) { first = false; if (char == '\uFEFF') continue }
                        offset++
                        validateTextChar(char)
                        if (char == '\n') { line++; column = 0; matched = 0; snippetSize = 0; snippetStart = 0; continue }
                        column++
                        if (snippetSize < snippet.size) { snippet[(snippetStart + snippetSize) % snippet.size] = char; snippetSize++ }
                        else { snippet[snippetStart] = char; snippetStart = (snippetStart + 1) % snippet.size }
                        val compared = if (caseSensitive) char else char.lowercaseChar()
                        while (matched > 0 && compared != needle[matched]) matched = prefix[matched - 1]
                        if (compared == needle[matched]) matched++
                        if (matched == needle.length) {
                            if (matches.length() == limit) { truncated = true; break@loop }
                            val text = CharArray(snippetSize) { snippet[(snippetStart + it) % snippet.size] }.concatToString()
                                .let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
                            matches.put(JSONObject().put("offset", offset - needle.length).put("line", line).put("column", column - needle.length + 1)
                                .put("text", (if (column > snippetSize) "…" else "") + text))
                            matched = prefix[matched - 1]
                        }
                    }
                }
            }
            JSONObject().put("id", attachment.id).put("bytes", attachment.bytes).put("matches", matches).put("truncated", truncated)
        }
    }

    suspend fun deleteAll(attachments: Collection<AiFileAttachment>) = guarded {
        archiveLock.withLock {
            if (!directory.exists() && !exists(directory)) return@withLock
            ensureDirectory()
            attachments.forEach { attachment -> AiFileAttachments.validate(attachment); deleteRegular(File(directory, "${attachment.id}.bin")) }
        }
    }

    /** Caller must provide the union from a fully successful history/draft scan. Unknown entries and links survive. */
    suspend fun pruneUnreferenced(retainedIds: Set<String>) = guarded {
        require(retainedIds.all(AiFileAttachments::validId))
        archiveLock.withLock {
            if (!directory.exists() && !exists(directory)) return@withLock
            ensureDirectory()
            val entries = directory.listFiles() ?: failure(AiFileAttachmentException.Reason.Unavailable)
            entries.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val extension = entry.extension
                val id = entry.nameWithoutExtension
                if (AiFileAttachments.validId(id) && (extension == "part" || (extension == "bin" && id !in retainedIds)))
                    deleteRegular(entry)
            }
        }
    }

    private fun textReader(attachment: AiFileAttachment): BufferedReader {
        AiFileAttachments.validate(attachment)
        val mime = attachment.mimeType.lowercase()
        if ((mime.startsWith("image/") && mime != "image/svg+xml") || mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime in setOf("application/pdf", "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
                "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/x-gzip",
                "application/x-bzip2", "application/x-xz", "application/zstd", "application/vnd.android.package-archive"))
            failure(AiFileAttachmentException.Reason.Unsupported)
        ensureDirectory()
        val file = File(directory, "${attachment.id}.bin")
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        // Android streams constructed with FileDescriptor borrow it. Transfer a duplicate
        // to the platform owner and always release the original, including failed preflight.
        val owned = try {
            val info = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(info.st_mode) || info.st_size != attachment.bytes) failure(AiFileAttachmentException.Reason.Unavailable)
            ParcelFileDescriptor.dup(descriptor)
        } finally { Os.close(descriptor) }
        val input = try { ParcelFileDescriptor.AutoCloseInputStream(owned) }
        catch (failure: Throwable) { owned.close(); throw failure }
        try {
            val header = ByteArray(16)
            val count = input.read(header)
            input.channel.position(0)
            if (knownBinaryHeader(header, maxOf(0, count))) failure(AiFileAttachmentException.Reason.Unsupported)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return BufferedReader(InputStreamReader(input, decoder), 8192)
        } catch (failure: Throwable) { input.close(); throw failure }
    }

    private fun knownBinaryHeader(header: ByteArray, length: Int): Boolean {
        fun starts(vararg bytes: Int): Boolean = length >= bytes.size && bytes.indices.all { (header[it].toInt() and 255) == bytes[it] }
        fun ascii(offset: Int, value: String): Boolean = length >= offset + value.length && value.indices.all { header[offset + it].toInt() == value[it].code }
        return ascii(0, "%PDF-") || ascii(0, "GIF87a") || ascii(0, "GIF89a") ||
            (ascii(0, "BM") && length >= 14 && (6..9).all { header[it] == 0.toByte() }) ||
            (ascii(0, "RIFF") && (ascii(8, "WEBP") || ascii(8, "WAVE") || ascii(8, "AVI "))) ||
            starts(0x50, 0x4b, 0x03, 0x04) || starts(0x50, 0x4b, 0x05, 0x06) || starts(0x50, 0x4b, 0x07, 0x08) ||
            starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) || starts(0xff, 0xd8, 0xff) ||
            starts(0x49, 0x49, 0x2a, 0) || starts(0x4d, 0x4d, 0, 0x2a) || starts(0x1f, 0x8b) ||
            starts(0x7f, 0x45, 0x4c, 0x46) || starts(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)
    }

    private fun validateUnicodeQuery(query: String) {
        var index = 0
        while (index < query.length) {
            val char = query[index++]
            if (char.isHighSurrogate()) {
                if (index >= query.length || !query[index++].isLowSurrogate()) failure(AiFileAttachmentException.Reason.Unsupported)
            } else if (char.isLowSurrogate()) failure(AiFileAttachmentException.Reason.Unsupported)
        }
    }

    private fun ensureDirectory() {
        if (!exists(directory) && !directory.mkdir()) failure(AiFileAttachmentException.Reason.Unavailable)
        if (!OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode)) failure(AiFileAttachmentException.Reason.Unavailable)
    }

    private fun usage(): Pair<Int, Long> {
        val entries = directory.listFiles() ?: failure(AiFileAttachmentException.Reason.Unavailable)
        var count = 0
        var bytes = 0L
        for (entry in entries) {
            val info = Os.lstat(entry.path)
            if (OsConstants.S_ISREG(info.st_mode)) {
                count++
                bytes = (bytes + info.st_size.coerceAtMost(AiFileAttachments.MAX_STORED_BYTES + 1))
                    .coerceAtMost(AiFileAttachments.MAX_STORED_BYTES + 1)
            }
        }
        return count to bytes
    }

    private fun exists(file: File): Boolean = try { Os.lstat(file.path); true } catch (missing: ErrnoException) {
        if (missing.errno == OsConstants.ENOENT) false else throw missing
    }

    private fun deleteRegular(file: File) {
        if (!exists(file)) return
        if (OsConstants.S_ISREG(Os.lstat(file.path).st_mode) && !file.delete()) failure(AiFileAttachmentException.Reason.Unavailable)
    }

    private fun validateTextChar(char: Char) {
        if (char.isISOControl() && char !in "\t\n\r\u000C") failure(AiFileAttachmentException.Reason.Unsupported)
    }

    private fun safeName(raw: String?): String = raw.orEmpty()
        .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .replace('/', '_').replace('\\', '_').trim().take(120)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "shared-file"

    private fun safeMime(raw: String?): String = raw?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.length <= 120 && it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) } ?: "application/octet-stream"

    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { currentCoroutineContext().ensureActive(); block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AiFileAttachmentException) { throw failure }
        catch (_: CharacterCodingException) { failure(AiFileAttachmentException.Reason.Unsupported) }
        catch (_: Exception) { failure(AiFileAttachmentException.Reason.Unavailable) }
    }

    private fun failure(reason: AiFileAttachmentException.Reason): Nothing = throw AiFileAttachmentException(reason)

    private companion object { val archiveLock = Mutex() }
}
