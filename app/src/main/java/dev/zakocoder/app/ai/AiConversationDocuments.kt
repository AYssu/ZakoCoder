package dev.zakocoder.app.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

/** SAF-only transfer boundary: provider URIs are never interpreted as filesystem paths. */
class AiConversationDocuments(private val context: Context) {
    suspend fun read(uri: Uri): TransferDocument = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val resolver = context.contentResolver
        currentCoroutineContext().ensureActive()
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.take(128)?.takeIf { it.isNotBlank() }
            ?: if (resolver.getType(uri)?.contains("json", ignoreCase = true) == true) "conversation.json" else "conversation.md"
        val bytes = (resolver.openInputStream(uri) ?: throw IOException("conversation_document_unreadable")).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > AiConversationTransfer.MAX_BYTES)
                        throw AiConversationTransferException(AiConversationTransferProblem.TooLarge)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        currentCoroutineContext().ensureActive()
        AiConversationTransfer.decode(bytes, name)
    }

    /** Only the opaque token crosses Activity recreation; transcript bytes stay in private cache. */
    suspend fun prepare(content: String): String = withContext(Dispatchers.IO) {
        val bytes = boundedBytes(content)
        require(bytes.isNotEmpty())
        preparedMutex.withLock {
            currentCoroutineContext().ensureActive()
            val directory = preparedDirectory(create = true)
            val now = System.currentTimeMillis()
            val retained = directory.listFiles()?.mapNotNull { file ->
                val base = file.name.removeSuffix(".new").removeSuffix(".bak")
                val token = base.removeSuffix(".txt")
                if (!base.endsWith(".txt") || !validToken(token)) null else token
            }?.distinct() ?: throw IOException("conversation_export_cache_unavailable")
            var live = 0
            for (token in retained) {
                val file = preparedFile(directory, token)
                val parts = listOf(file, File(file.path + ".new"), File(file.path + ".bak"))
                    .filter { Files.exists(it.toPath(), NOFOLLOW_LINKS) }
                if (parts.any { !Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) })
                    throw IOException("conversation_export_cache_invalid")
                if (parts.all { now - it.lastModified() > PREPARED_TTL_MILLIS }) AtomicFile(file).delete() else live++
            }
            if (live >= MAX_PREPARED) throw IOException("conversation_export_cache_full")
            val token = UUID.randomUUID().toString()
            val atomic = AtomicFile(preparedFile(directory, token))
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
            token
        }
    }

    suspend fun writePrepared(uri: Uri, token: String): Unit = withContext(Dispatchers.IO) {
        val bytes = preparedMutex.withLock {
            val file = preparedFile(preparedDirectory(), token)
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
                System.currentTimeMillis() - file.lastModified() > PREPARED_TTL_MILLIS)
                throw IOException("conversation_export_expired")
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > AiConversationTransfer.MAX_BYTES)
                            throw AiConversationTransferException(AiConversationTransferProblem.TooLarge)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().also { if (it.isEmpty()) throw IOException("conversation_export_empty") }
                }
            }
        }
        // A failed provider write keeps the prepared snapshot available for a retry.
        writeBytes(uri, bytes)
        withContext(NonCancellable) { discard(token) }
    }

    suspend fun discard(token: String): Unit = withContext(Dispatchers.IO) {
        preparedMutex.withLock {
            val directory = preparedDirectory()
            AtomicFile(preparedFile(directory, token)).delete()
        }
    }

    private fun validToken(token: String): Boolean = token.length == 36 &&
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private fun preparedFile(directory: File, token: String): File {
        require(validToken(token))
        return File(directory, "$token.txt")
    }

    private fun preparedDirectory(create: Boolean = false): File {
        val directory = File(context.cacheDir, "conversation-exports-v1")
        if (create && !Files.exists(directory.toPath(), NOFOLLOW_LINKS) && !directory.mkdir())
            throw IOException("conversation_export_cache_unavailable")
        if (Files.exists(directory.toPath(), NOFOLLOW_LINKS) && !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
            throw IOException("conversation_export_cache_invalid")
        return directory
    }

    private fun boundedBytes(content: String): ByteArray {
        if (content.length > AiConversationTransfer.MAX_BYTES)
            throw AiConversationTransferException(AiConversationTransferProblem.TooLarge)
        return content.toByteArray(Charsets.UTF_8).also {
            if (it.size > AiConversationTransfer.MAX_BYTES)
                throw AiConversationTransferException(AiConversationTransferProblem.TooLarge)
        }
    }

    suspend fun write(uri: Uri, content: String): Unit = withContext(Dispatchers.IO) {
        writeBytes(uri, boundedBytes(content))
    }

    private suspend fun writeBytes(uri: Uri, bytes: ByteArray) {
        require(uri.scheme == "content")
        currentCoroutineContext().ensureActive()
        val temporary = temporaryDocument(uri)
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            (context.contentResolver.openOutputStream(temporary.uri, "wt")
                ?: throw IOException("conversation_document_unwritable")).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                output.flush()
            }
            verifyDocument(temporary.uri, bytes, temporary.displayName)
            currentCoroutineContext().ensureActive()
            val committedUri = DocumentsContract.renameDocument(
                context.contentResolver, temporary.uri, temporary.originalName,
            ) ?: throw IOException("conversation_document_commit_unsupported")
            verifyDocument(committedUri, bytes, temporary.originalName)
            committed = true
        } catch (cancelled: CancellationException) {
            if (!committed) withContext(NonCancellable) { deleteDocument(temporary.uri) }
            throw cancelled
        } catch (error: Exception) {
            if (!committed) withContext(NonCancellable) { deleteDocument(temporary.uri) }
            throw IOException("conversation_document_unwritable", error)
        }
    }

    private data class TemporaryDocument(
        val uri: Uri,
        val displayName: String,
        val originalName: String,
    )

    private suspend fun temporaryDocument(uri: Uri): TemporaryDocument {
        val resolver = context.contentResolver
        val document = resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (nameIndex < 0 || flagsIndex < 0 || cursor.isNull(nameIndex)) return@use null
            cursor.getString(nameIndex).takeIf { it.isNotBlank() }?.let { it to cursor.getInt(flagsIndex) }
        } ?: throw IOException("conversation_document_unwritable")
        val (originalName, flags) = document
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0)
            throw IOException("conversation_document_replace_unsupported")

        if (!documentIsEmpty(uri)) throw IOException("conversation_document_exists")

        // CreateDocument grants only the selected document, not a generic parent URI. Treat its
        // content-empty result as the staging document and probe rename before any truncating write.
        val extension = originalName.substringAfterLast('.', "")
            .takeIf { it.length in 1..16 && it.all(Char::isLetterOrDigit) }
            ?.let { ".$it" }.orEmpty()
        val temporaryName = "zakocoder-${UUID.randomUUID()}-partial$extension"
        currentCoroutineContext().ensureActive()
        val renamed = DocumentsContract.renameDocument(resolver, uri, temporaryName)
            ?: throw IOException("conversation_document_replace_unsupported")
        val actualName = documentName(renamed)
        if (actualName != temporaryName) {
            runCatching { DocumentsContract.renameDocument(resolver, renamed, originalName) }
            throw IOException("conversation_document_replace_unsupported")
        }
        return TemporaryDocument(renamed, temporaryName, originalName)
    }

    private fun documentIsEmpty(uri: Uri): Boolean {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("conversation_document_unreadable")
        val size = descriptor.use { it.statSize }
        if (size >= 0) return size == 0L
        return (context.contentResolver.openInputStream(uri)
            ?: throw IOException("conversation_document_unreadable")).use { it.read() < 0 }
    }

    private fun documentName(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
    }

    private suspend fun verifyDocument(uri: Uri, expected: ByteArray, expectedName: String) {
        if (documentName(uri) != expectedName) throw IOException("conversation_document_commit_unsupported")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0
        (context.contentResolver.openInputStream(uri) ?: throw IOException("conversation_document_unreadable")).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > expected.size) throw IOException("conversation_document_unwritable")
                digest.update(buffer, 0, count)
            }
        }
        if (total != expected.size || !digest.digest().contentEquals(MessageDigest.getInstance("SHA-256").digest(expected)))
            throw IOException("conversation_document_unwritable")
    }

    private fun deleteDocument(uri: Uri) {
        try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { }
    }

    private companion object {
        val preparedMutex = Mutex()
        const val PREPARED_TTL_MILLIS = 60L * 60 * 1000
        const val MAX_PREPARED = 8
    }
}
