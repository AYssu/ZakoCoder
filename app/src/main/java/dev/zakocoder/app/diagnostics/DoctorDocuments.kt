package dev.zakocoder.app.diagnostics

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FilterOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixed ZIP entry names, bounded private snapshots and explicit SAF output; never exports a directory tree. */
internal class DoctorDocuments(private val context: Context) {
    suspend fun prepare(report: String, events: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val directory = directory(create = true)
            val existing = directory.listFiles() ?: throw IOException("doctor_cache_unavailable")
            val tokens = existing.mapNotNull { file ->
                val base = file.name.removeSuffix(".new").removeSuffix(".bak")
                base.removeSuffix(".zip").takeIf { base.endsWith(".zip") && valid(it) }
            }.distinct()
            var live = 0
            tokens.forEach { token ->
                val file = file(token)
                val parts = listOf(file, File(file.path + ".new"), File(file.path + ".bak"))
                    .filter { Files.exists(it.toPath(), NOFOLLOW_LINKS) }
                if (parts.any { !Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) throw IOException("doctor_cache_invalid")
                if (parts.all { expired(it) }) AtomicFile(file).delete() else live++
            }
            if (live >= 8) throw IOException("doctor_cache_full")
            val token = UUID.randomUUID().toString()
            val atomic = AtomicFile(file(token))
            val output = atomic.startWrite()
            try {
                // Do not close the AtomicFile stream until finishWrite has flushed and synced it.
                var total = 0L
                ZipOutputStream(object : FilterOutputStream(output) {
                    override fun close() { flush() }
                }).use { zip ->
                    for ((name, text) in listOf("README.txt" to NOTICE, "report.json" to report, "events.jsonl" to events)) {
                        currentCoroutineContext().ensureActive()
                        if (text.length > MAX_BYTES) throw IOException("doctor_size_limit")
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        total += bytes.size
                        if (total > MAX_BYTES) throw IOException("doctor_size_limit")
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
            token
        }
    }

    suspend fun writePrepared(uri: Uri, token: String): Unit = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val bytes = mutex.withLock {
            val prepared = file(token)
            if (!Files.isRegularFile(prepared.toPath(), NOFOLLOW_LINKS) || expired(prepared) || prepared.length() > MAX_BYTES)
                throw IOException("doctor_snapshot_expired")
            currentCoroutineContext().ensureActive()
            Files.newInputStream(prepared.toPath(), NOFOLLOW_LINKS).use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) throw IOException("doctor_size_limit")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        }
        try {
            withTimeout(60_000) { writeProvider(uri, bytes) }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("doctor_provider_timeout")
        }
        // Retain the snapshot until UI acknowledges success, including across recreation/cancellation.
    }

    private suspend fun writeProvider(uri: Uri, bytes: ByteArray): Unit = coroutineScope {
        val signal = CancellationSignal()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val finished = AtomicBoolean(false)
        // CancellationSignal interrupts cooperative providers; closing the descriptor releases a blocked pipe.
        // This watcher runs on IO, never in the UI's cancellation callback.
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                if (!finished.get()) {
                    try { signal.cancel() } finally {
                        try { descriptor.get()?.close() } catch (_: IOException) { }
                    }
                }
            }
        }
        try {
            val opened = context.contentResolver.openFileDescriptor(uri, "wt", signal)
                ?: throw IOException("doctor_output_unavailable")
            descriptor.set(opened)
            currentCoroutineContext().ensureActive()
            ParcelFileDescriptor.AutoCloseOutputStream(opened).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                output.flush()
            }
        } finally {
            finished.set(true)
            try { descriptor.getAndSet(null)?.close() }
            finally { closer.cancel() }
        }
    }

    suspend fun discard(token: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { AtomicFile(file(token)).delete() }
    }

    private fun directory(create: Boolean = false): File {
        val value = File(context.cacheDir, "doctor-exports-v1")
        if (create && !Files.exists(value.toPath(), NOFOLLOW_LINKS) && !value.mkdir()) throw IOException("doctor_cache_unavailable")
        if (Files.exists(value.toPath(), NOFOLLOW_LINKS) && !Files.isDirectory(value.toPath(), NOFOLLOW_LINKS))
            throw IOException("doctor_cache_invalid")
        return value
    }
    private fun file(token: String): File { require(valid(token)); return File(directory(), "$token.zip") }
    private fun valid(token: String) = token.length == 36 && runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)
    private fun expired(file: File) = System.currentTimeMillis() - file.lastModified() > 60L * 60 * 1000

    companion object {
        private val mutex = Mutex()
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val NOTICE = """ZakoCoder diagnostic bundle (schema 1)

report.json: app/device metadata, current conversation task/request/tool counters, environment and permission status.
events.jsonl: recent device-wide operational events with local correlation IDs and fixed error categories.

No message bodies, reasoning text, source files, commands, URLs, server names, headers, credentials, screenshots or raw logcat are included.
Workspace and conversation identifiers in report.json are hashed. Device model, timestamps and usage counters remain visible.
Events are bounded to two 256 KiB files and seven days; older events may be unavailable. Drop/write counters apply to this process only.
The snapshot can be taken while a task runs; individual subsystem samples may differ slightly in time. Incomplete requests are not necessarily failures.
Warnings are diagnostic hints, not proof of the cause. Process exit reason numbers use Android ApplicationExitInfo constants (Android 11+).
This file is saved locally only. Review before sharing; exporting does not upload or send anything to a model.
"""
    }
}
