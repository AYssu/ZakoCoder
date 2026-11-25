package dev.zakocoder.app.diagnostics

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-path metadata writer. It never reads throwable text, works synchronously so the record
 * survives process termination, and intentionally swallows every failure.
 */
internal class FatalMarker(private val directory: File) {
    private val current = File(directory, "fatal-marker.jsonl")
    private val previous = File(directory, "fatal-marker.previous.jsonl")

    init {
        runCatching {
            if (!directory.isDirectory) directory.mkdirs()
        }
    }

    fun write(throwable: Throwable) {
        synchronized(FILE_LOCK) {
            try {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
                if (Files.isSymbolicLink(current.toPath())) return
                val bytes = (encodeFatalRecord(System.currentTimeMillis(), throwable, Thread.currentThread()) + "\n")
                    .toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_FILE_BYTES) return
                if (shouldRotate(current.length(), bytes.size)) {
                    Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                FileOutputStream(current, true).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            } catch (_: Throwable) {
                // Never replace or obscure the original crash with a marker failure.
            }
        }
    }

    /** Returns raw JSONL lines in rotation order; unreadable, oversized, or linked files are skipped. */
    fun snapshot(): List<String> = synchronized(FILE_LOCK) {
        try {
            if (!directory.isDirectory) emptyList() else listOf(previous, current).flatMap { file ->
                try {
                    if (!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= MAX_FILE_BYTES) {
                        file.readLines(Charsets.UTF_8)
                    } else {
                        emptyList()
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        internal const val MAX_FILE_BYTES = 64L * 1024
        private val FILE_LOCK = Any()
    }
}

/**
 * The only crash values admitted here are the exception class and thread label. Throwable text,
 * stack traces, causes, paths, and endpoints are never traversed.
 */
internal fun encodeFatalRecord(time: Long, throwable: Throwable, thread: Thread, stage: String? = null): String {
    val record = JSONObject()
        .put("time", time)
        .put("exceptionClass", safeFatalLabel(throwable.javaClass.name, 256))
        .put("thread", safeFatalLabel(thread.name, 128, allowSpaces = true))
    stage?.let { safeFatalLabel(it, 64) }?.takeIf { it.isNotEmpty() }?.let { record.put("stage", it) }
    return record.toString()
}

/** Uses a strict character whitelist so path separators, URL punctuation, and control text cannot cross. */
private fun safeFatalLabel(value: String, maxLength: Int, allowSpaces: Boolean = false): String = buildString {
    for (character in value.take(maxLength)) {
        val allowed = character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character in "._-\$[]#()" || allowSpaces && character == ' '
        append(if (allowed) character else '_')
    }
}

/** A record may fill the file exactly; rotation starts only when the next bytes would exceed it. */
internal fun shouldRotate(currentBytes: Long, incomingBytes: Int): Boolean {
    if (currentBytes < 0 || incomingBytes < 0) return true
    val incoming = incomingBytes.toLong()
    return incoming > FatalMarker.MAX_FILE_BYTES ||
        currentBytes > FatalMarker.MAX_FILE_BYTES - incoming
}
