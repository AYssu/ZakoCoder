package dev.zakocoder.app.ai

import android.util.AtomicFile
import dev.zakocoder.app.editor.EditorFileStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

data class AiCheckpoint(val id: String, val created: Long, val turn: Int, val changed: Int, val limited: Boolean, val sealed: Boolean, val restored: Boolean, val conversation: String = "", val restoredCount: Int = 0)
class AiCheckpointRecovery internal constructor(internal val project: String, internal val checkpoint: String,
    internal val baselineManifest: String, internal val manifest: String, val changes: List<AiCheckpointChange>, val limited: Boolean, val restoredCount: Int)
data class AiCheckpointChange(val path: String, val before: String?, val after: String?, val directory: Boolean = false, val beforeMode: String? = null, val afterMode: String? = null)

/** Private, bounded byte snapshots. Never claims to reverse package/network/database side effects. Caller owns IO. */
class AiCheckpoints(private val base: File, private val root: (String) -> File) {
    private val files = AiProjectFiles(root)
    private data class Item(val bytes: ByteArray, val mode: String, val directory: Boolean = false) { val hash get() = if (directory) "directory" else hash(bytes) }
    private data class Snapshot(val files: Map<String, Item>, val paths: Set<String>, val limited: Boolean, val complete: Boolean, val directories: Map<String, Item>)
    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private val excluded = setOf("node_modules", "build", ".gradle", ".cache", ".venv", "venv", "target", "dist", "__pycache__", "backups")
    }
    private fun project(id: String) = File(base, hash(id.toByteArray())).apply { mkdirs() }
    fun workspaceRestorations(id: String): List<AiRestoration> {
        val journal = AiWorkspaceHistory(project(id))
        journal.observeLegacy(list(id))
        return journal.records()
    }
    fun workspaceContext(id: String) = AiWorkspaceHistory(project(id)).context(workspaceRestorations(id))
    /** Manifest metadata only: never reads source blobs or claims exhaustive/live attribution. Caller owns IO. */
    fun changeContext(id: String, checkpoint: String): String {
        val json = readManifest(directory(id, checkpoint))
        require(json.getString("id") == checkpoint)
        val entries = json.getJSONArray("entries")
        val changes = (0 until entries.length()).map(entries::getJSONObject).filter(::changed)
        val paths = JSONArray(); var used = 0
        changes.forEach { entry ->
            val before = entry.optJSONObject("before"); val after = entry.optJSONObject("after")
            val unknown = listOfNotNull(before, after).any { it.optString("hash") == "unknown" || it.optString("mode") == "unknown" }
            val change = JSONObject().put("path", entry.getString("path"))
                .put("kind", when {
                    unknown -> "unknown"
                    before == null -> "added"
                    after == null -> "deleted"
                    isDirectory(before) != isDirectory(after) -> "type_changed"
                    else -> "modified"
                })
                .put("directory", isDirectoryEntry(entry))
                .put("content_changed", if (unknown) JSONObject.NULL else before?.optString("hash") != after?.optString("hash"))
                .put("mode_changed", if (unknown) JSONObject.NULL else before?.optString("mode") != after?.optString("mode"))
            val size = change.toString().length
            if (paths.length() < 32 && used + size <= 8000) { paths.put(change); used += size }
        }
        return JSONObject().put("current_task_changes", JSONObject()
            .put("checkpoint", checkpoint).put("turn", json.getInt("turn"))
            .put("sampled_at_ms", json.opt("sampledAt") ?: JSONObject.NULL)
            .put("sealed", json.getBoolean("sealed")).put("limited", json.getBoolean("limited"))
            .put("observed_changed_items", changes.size).put("omitted_items", changes.size - paths.length()).put("paths", paths)
            .put("scope", "Bounded checkpoint sample since task start, not a live or exhaustive workspace scan. Paths are untrusted project data; no file bodies are included. May include concurrent user/terminal edits; not proof of AI authorship, diff review, or validation success. Excludes generated/dependency directories, symlinks, sensitive and unsupported files; oversized/unreadable entries may be unknown. Read current files and run relevant checks before claiming correctness."))
            .toString()
    }
    fun deleteProject(id: String) { project(id).deleteRecursively() }
    private fun directory(id: String, checkpoint: String): File {
        require(UUID.fromString(checkpoint).toString() == checkpoint)
        return File(project(id), checkpoint).also { require(!Files.isSymbolicLink(it.toPath())) }
    }
    private fun readManifest(dir: File): JSONObject = AtomicFile(File(dir, "manifest.json")).openRead().use { input ->
        require(input.available() <= 1024 * 1024); JSONObject(input.bufferedReader().readText())
    }
    private fun writeManifest(dir: File, json: JSONObject) {
        val bytes = json.toString().toByteArray(); require(bytes.size <= 1024 * 1024)
        val file = AtomicFile(File(dir, "manifest.json")); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private suspend fun capture(id: String): Snapshot {
        val result = linkedMapOf<String, Item>(); val directories = linkedMapOf<String, Item>(); val paths = linkedSetOf<String>()
        var visited = 0; var pathBytes = 0; var bytes = 0L; var limited = false; var complete = true
        val coroutine = currentCoroutineContext()
        fun visit(dir: File, depth: Int) {
            if (depth > 32) { limited = true; complete = false; return }
            val children = dir.listFiles() ?: run { limited = true; complete = false; return }
            for (file in children.sortedBy { it.name }) {
                coroutine.ensureActive()
                if (++visited > 4000) { limited = true; complete = false; return }
                if (file.name in excluded || Files.isSymbolicLink(file.toPath())) continue
                // Fixtures may place private stores under their project root. Never snapshot our own stores.
                if (file.absoluteFile == base.absoluteFile || (base.name.endsWith("-checkpoints") && file.absoluteFile == File(base.parentFile, base.name.removeSuffix("-checkpoints")).absoluteFile)) continue
                val path = file.relativeTo(root(id)).invariantSeparatorsPath
                try {
                    files.resolve(id, path)
                    val size = path.toByteArray().size
                    if (pathBytes + size > 200000) { limited = true; complete = false; continue }
                    pathBytes += size; paths += path
                    if (file.isDirectory) {
                        if (directories.size >= 500) { limited = true; complete = false; continue }
                        directories[path] = Item(byteArrayOf(), PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS)), true)
                        visit(file, depth + 1); continue
                    }
                    if (!file.isFile || file.length() > EditorFileStore.MAX_BYTES || result.size >= 500 || bytes + file.length() > 16 * 1024 * 1024) { limited = true; continue }
                    val before = files.read(id, path)
                    val data = Files.newByteChannel(file.toPath(), setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                        check(channel.size() <= EditorFileStore.MAX_BYTES)
                        val out = java.io.ByteArrayOutputStream(); val buffer = java.nio.ByteBuffer.allocate(8192)
                        while (channel.read(buffer) != -1) { check(out.size() + buffer.position() <= EditorFileStore.MAX_BYTES); out.write(buffer.array(), 0, buffer.position()); buffer.clear() }
                        out.toByteArray()
                    }
                    if (hash(data) != before.digest) { limited = true; continue }
                    result[path] = Item(data, PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS)))
                    bytes += data.size
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { limited = true }
            }
        }
        visit(root(id), 0)
        return Snapshot(result, paths, limited, complete, directories)
    }
    private fun blob(dir: File, item: Item): String {
        val name = item.hash
        val file = File(dir, name)
        if (!file.exists()) java.io.FileOutputStream(file).use { it.write(item.bytes); it.fd.sync() }
        return name
    }
    private fun metadata(dir: File, item: Item?): Any = item?.let { JSONObject().put("hash", if (it.directory) "directory" else blob(dir, it)).put("mode", it.mode) } ?: JSONObject.NULL
    suspend fun begin(id: String, conversation: String, turn: Int): String {
        workspaceRestorations(id) // Migrate restoration facts before pruning old manifests.
        val snapshot = capture(id); val key = UUID.randomUUID().toString(); val dir = directory(id, key)
        check(dir.mkdir())
        try {
            val entries = JSONArray()
            (snapshot.files + snapshot.directories).forEach { (path, item) -> entries.put(JSONObject().put("path", path).put("before", metadata(dir, item)).put("after", metadata(dir, item))) }
            writeManifest(dir, JSONObject().put("id", key).put("created", System.currentTimeMillis()).put("conversation", conversation).put("turn", turn)
                .put("limited", snapshot.limited).put("complete", snapshot.complete).put("sampledAt", System.currentTimeMillis()).put("sealed", false).put("restored", false)
                .put("initial", JSONArray(snapshot.paths.toList())).put("entries", entries).put("directorySnapshots", true))
            // Keep five task snapshots per project; this retention never touches source files.
            list(id).drop(5).forEach { directory(id, it.id).deleteRecursively() }
            return key
        } catch (error: Exception) { dir.deleteRecursively(); throw error }
    }
    suspend fun update(id: String, checkpoint: String, sealed: Boolean = false) {
        val dir = directory(id, checkpoint); val json = readManifest(dir)
        val snapshot = capture(id)
        applySnapshot(id, dir, json, snapshot)
        json.put("sampledAt", System.currentTimeMillis())
        json.put("sealed", sealed)
        writeManifest(dir, json)
        pruneBlobs(dir, json)
    }
    private fun applySnapshot(id: String, dir: File, json: JSONObject, snapshot: Snapshot) {
        val entries = json.getJSONArray("entries")
        val initial = json.getJSONArray("initial").let { a -> (0 until a.length()).map(a::getString).toSet() }
        val known = (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }.toSet()
        var fileCount = (0 until entries.length()).count { !isDirectoryEntry(entries.getJSONObject(it)) }
        var directoryCount = entries.length() - fileCount
        (snapshot.files + if (json.optBoolean("directorySnapshots")) snapshot.directories else emptyMap()).filterKeys { it !in initial && it !in known }.forEach { (path, item) ->
            if ((if (item.directory) directoryCount else fileCount) >= 500) { json.put("limited", true); return@forEach }
            if (json.getBoolean("complete")) {
                entries.put(JSONObject().put("path", path).put("before", JSONObject.NULL).put("after", metadata(dir, item)))
                if (item.directory) directoryCount++ else fileCount++
            }
        }
        val restored = json.optJSONArray("restoredPaths")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty()
        repeat(entries.length()) { i ->
            val e = entries.getJSONObject(i); val path = e.getString("path")
            if (path in restored || json.optJSONObject("directoryStages")?.has(path) == true) return@repeat
            val current = snapshot.files[path] ?: snapshot.directories[path]
            if (current != null) e.put("after", metadata(dir, current))
            else if (snapshot.complete && path !in snapshot.paths && !files.resolve(id, path).exists()) e.put("after", JSONObject.NULL)
            // An unreadable/unsupported result is never an authority to overwrite later.
            else e.put("after", JSONObject().put("hash", "unknown").put("mode", "unknown"))
        }
        json.put("limited", json.getBoolean("limited") || snapshot.limited)
    }
    private fun pruneBlobs(dir: File, json: JSONObject, extra: JSONObject? = null) {
        val entries = json.getJSONArray("entries")
        val extraEntries = extra?.getJSONArray("entries") ?: JSONArray()
        val extraHashes = (0 until extraEntries.length()).flatMap { i -> listOfNotNull(extraEntries.getJSONObject(i).optJSONObject("before")?.optString("hash"), extraEntries.getJSONObject(i).optJSONObject("after")?.optString("hash")) }
        val keep = (extraHashes + (0 until entries.length()).flatMap { i -> listOfNotNull(entries.getJSONObject(i).optJSONObject("before")?.optString("hash"), entries.getJSONObject(i).optJSONObject("after")?.optString("hash")) }).toSet()
        dir.listFiles().orEmpty().filter { Regex("[a-f0-9]{64}").matches(it.name) && it.name !in keep }.forEach { it.delete() }
    }
    private fun isDirectory(meta: JSONObject?) = meta?.optString("hash") == "directory"
    private fun isDirectoryEntry(e: JSONObject) = isDirectory(e.optJSONObject("before")) || isDirectory(e.optJSONObject("after"))
    private fun changed(e: JSONObject) = e.opt("before").toString() != e.opt("after").toString()
    fun list(id: String): List<AiCheckpoint> = project(id).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        try {
            val j = readManifest(dir); val entries = j.getJSONArray("entries")
            AiCheckpoint(j.getString("id"), j.getLong("created"), j.getInt("turn"), (0 until entries.length()).count { changed(entries.getJSONObject(it)) }, j.getBoolean("limited"), j.getBoolean("sealed"), j.optBoolean("restored"), j.optString("conversation"), j.optJSONArray("restoredPaths")?.length() ?: if (j.optBoolean("restored")) (0 until entries.length()).count { changed(entries.getJSONObject(it)) } else 0)
        } catch (_: Exception) { null }
    }.sortedByDescending { it.created }
    suspend fun reviewInterrupted(id: String, checkpoint: String): AiCheckpointRecovery {
        val dir = directory(id, checkpoint); val json = readManifest(dir)
        require(!json.getBoolean("sealed") && !json.optBoolean("restored"))
        // Freeze an explicitly reviewed current version, never silently attribute post-crash edits to AI.
        reconcileIntent(id, checkpoint, dir, json)
        val baseline = json.toString()
        applySnapshot(id, dir, json, capture(id))
        pruneBlobs(dir, json, JSONObject(baseline))
        val done = json.optJSONArray("restoredPaths")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty()
        return AiCheckpointRecovery(id, checkpoint, baseline, json.toString(), changes(dir, json).filter { it.path !in done }, json.getBoolean("limited"), done.size)
    }
    fun changes(id: String, checkpoint: String): List<AiCheckpointChange> {
        val dir = directory(id, checkpoint)
        return changes(dir, readManifest(dir))
    }
    private fun changes(dir: File, json: JSONObject): List<AiCheckpointChange> {
        val entries = json.getJSONArray("entries")
        fun text(e: JSONObject, key: String): String? {
            val meta = e.optJSONObject(key) ?: return null
            val hash = meta.getString("hash"); if (hash == "directory") return meta.getString("mode") + "\n"
            if (hash == "unknown") return "[Result unavailable; cannot restore automatically.]"
            require(Regex("[a-f0-9]{64}").matches(hash))
            val bytes = File(dir, hash).readBytes(); require(hash(bytes) == hash)
            return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
        fun mode(e: JSONObject, key: String): String? = e.optJSONObject(key)?.optString("mode")?.takeIf {
            it.matches(Regex("[r-][w-][x-][r-][w-][x-][r-][w-][x-]"))
        }
        return (0 until entries.length()).map(entries::getJSONObject).filter(::changed).map {
            AiCheckpointChange(it.getString("path"), text(it, "before"), text(it, "after"), isDirectoryEntry(it), mode(it, "before"), mode(it, "after"))
        }
    }
    private fun versionMatches(id: String, path: String, expected: JSONObject?): Boolean {
            val target = files.resolve(id, path)
            return if (expected == null) !Files.exists(target.toPath(), NOFOLLOW_LINKS)
            else if (isDirectory(expected)) Files.isDirectory(target.toPath(), NOFOLLOW_LINKS) &&
                PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS)) == expected.getString("mode")
            else expected.getString("hash") != "unknown" && target.isFile && !Files.isSymbolicLink(target.toPath()) &&
                files.read(id, path).digest == expected.getString("hash") &&
                PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS)) == expected.getString("mode")
    }
    private fun reconcileIntent(id: String, checkpoint: String, dir: File, json: JSONObject) {
        val journal = AiWorkspaceHistory(project(id))
        val array = json.getJSONArray("entries")
        val entries = (0 until array.length()).map(array::getJSONObject).filter(::changed)
        val restored = json.optJSONArray("restoredPaths") ?: JSONArray()
        // A durable per-file intent closes the rename -> manifest progress crash window.
        val pending = journal.records().firstOrNull { it.checkpoint == checkpoint && it.pendingPath.isNotEmpty() }
        val interrupted = pending?.let { event -> entries.firstOrNull { e ->
            e.getString("path") == event.pendingPath && e.opt("before").toString() == event.pendingBefore && e.opt("after").toString() == event.pendingAfter
        } }
        if (pending != null && interrupted != null && versionMatches(id, pending.pendingPath, interrupted.optJSONObject("before"))) {
            if ((0 until restored.length()).none { restored.getString(it) == pending.pendingPath }) restored.put(pending.pendingPath)
            json.put("restoredPaths", restored); writeManifest(dir, json)
            journal.save(pending.copy(confirmed = restored.length(), pendingPath = "", pendingBefore = "", pendingAfter = "",
                paths = (pending.paths + pending.pendingPath.take(240)).distinct().takeLast(20)))
        }
    }
    fun restore(id: String, checkpoint: String, editorOpen: (String) -> Boolean): Int = restore(id, checkpoint, null, editorOpen)
    fun restore(id: String, checkpoint: String, reviewed: AiCheckpointRecovery?, editorOpen: (String) -> Boolean): Int {
        val dir = directory(id, checkpoint); val original = readManifest(dir)
        require(!original.optBoolean("restored"))
        if (!original.getBoolean("sealed")) require(reviewed != null) { "Interrupted snapshot requires current-file review" }
        val json = if (reviewed == null) original else {
            require(reviewed.project == id && reviewed.checkpoint == checkpoint && !original.getBoolean("sealed"))
            require(original.toString() == reviewed.baselineManifest) { "Snapshot changed since review" }
            JSONObject(reviewed.manifest)
        }
        val all = json.getJSONArray("entries").let { a -> (0 until a.length()).map(a::getJSONObject) }
        val changed = all.filter(::changed)
        val stages = json.optJSONObject("directoryStages") ?: JSONObject()
        // Mode changes needed to write children are journalled even if the task did not change that directory.
        val parents = all.filter { e ->
            val path = e.getString("path"); val before = e.optJSONObject("before")
            isDirectory(before) && (stages.has(path) || (changed.any { it.getString("path").startsWith("$path/") } &&
                (!Files.isDirectory(files.resolve(id, path).toPath(), NOFOLLOW_LINKS) ||
                    e.optJSONObject("after")?.optString("mode")?.let { it.length == 9 && (it[1] != 'w' || it[2] != 'x') } == true)))
        }
        val operations = (changed + parents).distinctBy { it.getString("path") }
        val journal = AiWorkspaceHistory(project(id)); journal.observeLegacy(list(id))
        reconcileIntent(id, checkpoint, dir, json)
        val restored = json.optJSONArray("restoredPaths") ?: JSONArray()
        val done = (0 until restored.length()).map(restored::getString).toSet()
        val remaining = operations.filter { it.getString("path") !in done }
        val directories = remaining.filter { isDirectory(it.optJSONObject("before")) }
        val deleting = remaining.filter { it.isNull("before") }.map { it.getString("path") }.toSet()
        fun matches(entry: JSONObject) {
            val path = entry.getString("path"); check(!editorOpen(path)) { "Editor open" }
            val after = entry.optJSONObject("after")
            val stage = stages.optJSONObject(path)
            check(versionMatches(id, path, after) || (isDirectoryEntry(entry) && stage != null && versionMatches(id, path, stage))) { "File conflict: $path" }
        }
        fun parentAvailable(path: String) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                val target = files.resolve(id, parent)
                check(Files.isDirectory(target.toPath(), NOFOLLOW_LINKS) ||
                    (!Files.exists(target.toPath(), NOFOLLOW_LINKS) && directories.any { it.getString("path") == parent })) { "Missing parent: $path" }
                parent = parent.substringBeforeLast('/', "")
            }
            check(Files.isDirectory(root(id).toPath(), NOFOLLOW_LINKS))
        }
        fun removableDirectory(path: String) {
            val target = files.resolve(id, path)
            val children = target.listFiles(); check(children != null && children.size <= 1000) { "Unreadable directory: $path" }
            children.forEach { child ->
                val relative = "$path/${child.name}"; files.resolve(id, relative)
                check(relative in deleting) { "Unrecorded directory content: $relative" }
                if (Files.isDirectory(child.toPath(), NOFOLLOW_LINKS)) removableDirectory(relative)
            }
        }
        // Whole-set preflight includes contents of directories to remove, before any source mutation.
        remaining.forEach { e ->
            val path = e.getString("path"); val before = e.optJSONObject("before"); val after = e.optJSONObject("after")
            require(before == null || after == null || isDirectory(before) == isDirectory(after)) { "File/directory replacement requires manual review: $path" }
            matches(e)
            if (isDirectory(after) && before == null) removableDirectory(path)
            if (before != null) parentAvailable(path)
            listOfNotNull(before, if (reviewed != null) after else null).filterNot(::isDirectory).forEach { meta ->
                val digest = meta.getString("hash"); require(Regex("[a-f0-9]{64}").matches(digest))
                check(hash(File(dir, digest).readBytes()) == digest)
            }
        }
        var event = journal.begin(checkpoint, json.optString("conversation"), json.getInt("turn"), operations.size, done.size)
        fun intent(e: JSONObject) {
            event = event.copy(pendingPath = e.getString("path"), pendingBefore = e.opt("before").toString(), pendingAfter = e.opt("after").toString())
            journal.save(event)
        }
        fun confirmed(e: JSONObject) {
            val path = e.getString("path")
            restored.put(path); json.put("restoredPaths", restored); writeManifest(dir, json)
            event = event.copy(confirmed = restored.length(), pendingPath = "", pendingBefore = "", pendingAfter = "", paths = (event.paths + path.take(240)).takeLast(20))
            journal.save(event)
        }
        try {
            // Reviewed after versions survive a kill during the first restoration.
            writeManifest(dir, json)
            (directories + remaining.filter { isDirectory(it.optJSONObject("after")) && it.isNull("before") }).sortedBy { it.getString("path").count { c -> c == '/' } }.forEach { e ->
                matches(e)
                val path = e.getString("path"); val target = files.resolve(id, path)
                val mode = (e.optJSONObject("after") ?: e.getJSONObject("before")).getString("mode").toCharArray()
                require(mode.size == 9); mode[0] = 'r'; mode[1] = 'w'; mode[2] = 'x'
                val stage = JSONObject().put("hash", "directory").put("mode", String(mode))
                if (!versionMatches(id, path, stage)) {
                    // A stage is an intent, persisted before mkdir/chmod; only this exact temporary mode is accepted on retry.
                    stages.put(path, stage); json.put("directoryStages", stages); writeManifest(dir, json); intent(e)
                    matches(e)
                    if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(target.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(String(mode))))
                    check(Files.isDirectory(files.resolve(id, path).toPath(), NOFOLLOW_LINKS))
                    Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(String(mode)))
                }
            }
            remaining.filterNot(::isDirectoryEntry).forEach { e ->
                matches(e); intent(e)
                val path = e.getString("path"); val target = files.resolve(id, path); val before = e.optJSONObject("before")
                if (before == null) Files.delete(target.toPath()) else {
                    val digest = before.getString("hash"); val bytes = File(dir, digest).readBytes(); check(hash(bytes) == digest)
                    check(target.parentFile.isDirectory) { "Missing parent: $path" }
                    val tmp = Files.createTempFile(target.parentFile.toPath(), ".zakocoder-restore-", ".tmp")
                    try {
                        java.io.FileOutputStream(tmp.toFile()).use { it.write(bytes); it.fd.sync() }
                        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(before.getString("mode")))
                        matches(e); Files.move(tmp, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(tmp) }
                }
                confirmed(e)
            }
            // Deletions are strictly non-recursive; a newly added child prevents removal.
            remaining.filter { isDirectory(it.optJSONObject("after")) && it.isNull("before") }
                .sortedByDescending { it.getString("path").count { c -> c == '/' } }.forEach { e ->
                    matches(e); intent(e)
                    val target = files.resolve(id, e.getString("path"))
                    check(target.listFiles()?.isEmpty() == true); Files.delete(target.toPath()); confirmed(e)
                }
            // Restrictive original permissions are applied only after children have been restored.
            directories.sortedByDescending { it.getString("path").count { c -> c == '/' } }.forEach { e ->
                matches(e); intent(e)
                Files.setPosixFilePermissions(files.resolve(id, e.getString("path")).toPath(), PosixFilePermissions.fromString(e.getJSONObject("before").getString("mode")))
                confirmed(e)
            }
            json.put("restored", true); writeManifest(dir, json)
            journal.save(event.copy(status = "completed", finished = System.currentTimeMillis()))
            return remaining.size
        } catch (error: Exception) {
            try { journal.save(event.copy(status = "partial", finished = System.currentTimeMillis())) }
            catch (recordError: Exception) { error.addSuppressed(recordError) }
            throw error
        }
    }
}
