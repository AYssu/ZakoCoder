package dev.zakocoder.app.memory

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class MemoryFailure { Corrupt, UnsupportedVersion, ReadWrite, InvalidEntry, Capacity }
internal class MemoryStorageException(val failure: MemoryFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/** All disk work is bounded and serialized. A damaged or newer store is never silently replaced. */
internal class MemoryRepository(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "agent-memory"))
    private val file = AtomicFile(File(directory, "v1.json"))

    suspend fun load(): List<AgentMemory> = withContext(Dispatchers.IO) { lock.withLock { read() } }

    suspend fun change(transform: (List<AgentMemory>) -> List<AgentMemory>): List<AgentMemory> = withContext(Dispatchers.IO) {
        lock.withLock {
            val entries = transform(read())
            if (entries.size > MemoryPolicy.MAX_ENTRIES) throw MemoryStorageException(MemoryFailure.Capacity)
            try { entries.forEach(MemoryPolicy::validate); require(entries.map { it.id }.distinct().size == entries.size) }
            catch (failure: IllegalArgumentException) { throw MemoryStorageException(MemoryFailure.InvalidEntry, failure) }
            val json = JSONObject().put("version", MemoryPolicy.SCHEMA).put("entries", JSONArray().apply {
                entries.forEach { entry -> put(JSONObject().put("id", entry.id).put("title", entry.title)
                    .put("content", entry.content).put("source", entry.source).put("createdAt", entry.createdAt)
                    .put("updatedAt", entry.updatedAt).put("enabled", entry.enabled).put("core", entry.core).put("pending", entry.pending)) }
            })
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MemoryPolicy.MAX_FILE_BYTES) throw MemoryStorageException(MemoryFailure.Capacity)
            currentCoroutineContext().ensureActive()
            if (!directory.exists() && !directory.mkdirs()) throw MemoryStorageException(MemoryFailure.ReadWrite)
            val output = try { file.startWrite() } catch (failure: java.io.IOException) {
                throw MemoryStorageException(MemoryFailure.ReadWrite, failure)
            }
            try {
                output.write(bytes)
                currentCoroutineContext().ensureActive()
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                if (failure is java.io.IOException) throw MemoryStorageException(MemoryFailure.ReadWrite, failure)
                throw failure
            }
            entries
        }
    }

    private suspend fun read(): List<AgentMemory> {
        val bytes = try {
            file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MemoryPolicy.MAX_FILE_BYTES) throw MemoryStorageException(MemoryFailure.Corrupt)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (missing: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
            throw MemoryStorageException(MemoryFailure.ReadWrite, missing)
        } catch (failure: java.io.IOException) { throw MemoryStorageException(MemoryFailure.ReadWrite, failure) }
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JSONObject(text)
            require(json.get("version") is Int)
            if (json.getInt("version") != MemoryPolicy.SCHEMA) throw MemoryStorageException(MemoryFailure.UnsupportedVersion)
            val array = json.getJSONArray("entries")
            require(array.length() <= MemoryPolicy.MAX_ENTRIES)
            List(array.length()) { index ->
                val entry = array.getJSONObject(index)
                listOf("id", "title", "content", "source").forEach { require(entry.get(it) is String) }
                listOf("enabled", "core", "pending").forEach { require(entry.get(it) is Boolean) }
                listOf("createdAt", "updatedAt").forEach { require(entry.get(it) is Int || entry.get(it) is Long) }
                AgentMemory(entry.getString("id"), entry.getString("title"), entry.getString("content"), entry.getString("source"),
                    entry.getLong("createdAt"), entry.getLong("updatedAt"), entry.getBoolean("enabled"), entry.getBoolean("core"),
                    entry.getBoolean("pending")).also(MemoryPolicy::validate)
            }.also { require(it.map { entry -> entry.id }.distinct().size == it.size) }
        } catch (failure: MemoryStorageException) { throw failure }
        catch (failure: org.json.JSONException) { throw MemoryStorageException(MemoryFailure.Corrupt, failure) }
        catch (failure: IllegalArgumentException) { throw MemoryStorageException(MemoryFailure.Corrupt, failure) }
        catch (failure: java.nio.charset.CharacterCodingException) { throw MemoryStorageException(MemoryFailure.Corrupt, failure) }
    }

    private companion object { val lock = Mutex() }
}
