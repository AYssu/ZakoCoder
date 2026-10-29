package dev.zakocoder.app.editor

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

class EditorFileException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { UNSUPPORTED, LIMIT, PATH, CONFLICT }
}

data class FileBaseline(val projectId: String, val path: String, val text: String, val digest: String, val bom: Boolean)

/** Blocking, bounded IO. The caller owns Dispatchers.IO. Never resolves a SAF URI. */
class EditorFileStore(private val projectRoot: (String) -> File) {
    companion object { const val MAX_BYTES = 1024 * 1024; const val MAX_LINES = 20000; const val MAX_COLUMN = 20000 }
    private fun fail(reason: EditorFileException.Reason): Nothing = throw EditorFileException(reason)
    private fun resolve(id: String, path: String): File {
        val root = projectRoot(id)
        if (path.isEmpty() || path.startsWith('/') || path.contains('\\')) fail(EditorFileException.Reason.PATH)
        var file = root
        path.split('/').forEach { part ->
            if (part.isEmpty() || part == "." || part == ".." || part.any(Char::isISOControl)) fail(EditorFileException.Reason.PATH)
            file = File(file, part)
            if (Files.isSymbolicLink(file.toPath())) fail(EditorFileException.Reason.PATH)
        }
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            fail(EditorFileException.Reason.PATH)
        }
        return file
    }
    private fun read(file: File): ByteArray {
        Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() > MAX_BYTES) fail(EditorFileException.Reason.LIMIT)
            val out = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) != -1) {
                if (out.size() + buffer.position() > MAX_BYTES) fail(EditorFileException.Reason.LIMIT)
                out.write(buffer.array(), 0, buffer.position()); buffer.clear()
            }
            return out.toByteArray()
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checkText(text: String) {
        if ('\u0000' in text) fail(EditorFileException.Reason.UNSUPPORTED)
        var lines = 1
        var column = 0
        var previous = '\u0000'
        for (c in text) {
            if (c == '\n' || c == '\r') { if (c == '\r' || previous != '\r') lines++; column = 0 } else column++
            previous = c
            if (lines > MAX_LINES || column > MAX_COLUMN) fail(EditorFileException.Reason.LIMIT)
        }
    }
    @Synchronized fun open(id: String, path: String): FileBaseline {
        val bytes = read(resolve(id, path))
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { fail(EditorFileException.Reason.UNSUPPORTED) }
        checkText(text)
        return FileBaseline(id, path, text, digest(bytes), bom)
    }
    @Synchronized fun save(baseline: FileBaseline, text: String): FileBaseline {
        checkText(text)
        val encoded = try { Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text)) }
        catch (_: java.nio.charset.CharacterCodingException) { fail(EditorFileException.Reason.UNSUPPORTED) }
        val bytes = (if (baseline.bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else byteArrayOf()) + ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (bytes.size > MAX_BYTES) fail(EditorFileException.Reason.LIMIT)
        val target = resolve(baseline.projectId, baseline.path)
        if (digest(read(target)) != baseline.digest) fail(EditorFileException.Reason.CONFLICT)
        val permissions = Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS)
        val temporary = Files.createTempFile(requireNotNull(target.parentFile).toPath(), ".zakocoder-edit-", ".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.setPosixFilePermissions(temporary, permissions)
            // Recheck just before committing: external terminal writes do not share our lock.
            if (resolve(baseline.projectId, baseline.path) != target || digest(read(target)) != baseline.digest) fail(EditorFileException.Reason.CONFLICT)
            Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return baseline.copy(text = text, digest = digest(bytes))
    }
}
