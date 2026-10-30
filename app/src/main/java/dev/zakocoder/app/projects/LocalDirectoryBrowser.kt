package dev.zakocoder.app.projects

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import dev.zakocoder.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files

internal data class LocalDirectory(val name: String, val path: String)
internal data class LocalDirectoryState(
    val loading: Boolean = true, val current: LocalDirectory? = null,
    val roots: List<LocalDirectory> = emptyList(), val directories: List<LocalDirectory> = emptyList(),
    val parent: String? = null, val selectable: Boolean = false, val truncated: Boolean = false, val error: String = "",
)

/** Browses actual accessible storage directories. Never derives paths from document-provider URIs. */
internal class LocalDirectoryBrowser(
    private val context: Context, private val scope: CoroutineScope,
    private val volumes: () -> List<File> = {
        if (Build.VERSION.SDK_INT >= 30) context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        else @Suppress("DEPRECATION") listOf(Environment.getExternalStorageDirectory())
    },
) {
    private val mutable = MutableStateFlow(LocalDirectoryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    /** Navigation starts with one page; refreshing the same folder retains its identity. */
    fun open(path: String? = null) = load(path, 200, path != null && path == mutable.value.current?.path)

    fun more() {
        val current = mutable.value
        if (current.loading || !current.truncated || current.current == null) return
        load(current.current.path, (current.directories.size.toLong() + 200).coerceAtMost(Int.MAX_VALUE - 1L).toInt(), true)
    }

    private fun load(path: String?, limit: Int, preserve: Boolean) {
        val previous = if (preserve) mutable.value else LocalDirectoryState()
        val owner = ++generation
        job?.cancel()
        // Retain the current page during pagination, but never offer a stale binding action.
        mutable.value = previous.copy(loading = true, selectable = false, error = "")
        job = scope.launch {
            val next = withContext(Dispatchers.IO) {
                try {
                    val roots = volumes().map { it.canonicalFile }.distinct().filter { it.isDirectory && it.canRead() }
                    val root = path?.let(::File) ?: roots.firstOrNull()
                        ?: throw IllegalStateException(context.getString(R.string.local_browser_unavailable))
                    require(root.isAbsolute && !Files.isSymbolicLink(root.toPath()))
                    val canonical = root.canonicalFile
                    val volume = roots.firstOrNull { canonical.toPath().startsWith(it.toPath()) }
                        ?: throw IllegalArgumentException(context.getString(R.string.local_browser_unavailable))
                    var part: File? = root
                    while (part != null && part.toPath().startsWith(volume.toPath())) {
                        require(!Files.isSymbolicLink(part.toPath()))
                        part = part.parentFile
                    }
                    val children = canonical.listFiles() ?: throw IllegalStateException(context.getString(R.string.local_browser_unavailable))
                    val directories = children.asSequence().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory && it.canRead() && it.name != "Android" }
                        .sortedWith(compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }).take(limit + 1).toList()
                    val selectable = runCatching { LocalProjectBindings(File(context.filesDir, "projects"), listOf(context.filesDir, context.cacheDir, File(context.applicationInfo.dataDir))).validate(canonical.path); true }.getOrDefault(false)
                    LocalDirectoryState(loading = false, current = LocalDirectory(canonical.name, canonical.path),
                        roots = roots.map { LocalDirectory(it.name, it.path) },
                        directories = directories.take(limit).map { LocalDirectory(it.name, it.path) },
                        parent = canonical.parentFile?.takeIf { it.toPath().startsWith(volume.toPath()) }?.path,
                        selectable = selectable, truncated = directories.size > limit)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { previous.copy(loading = false, selectable = false, error = context.getString(R.string.local_browser_unavailable)) }
            }
            if (owner == generation) mutable.value = next
        }
    }
}
