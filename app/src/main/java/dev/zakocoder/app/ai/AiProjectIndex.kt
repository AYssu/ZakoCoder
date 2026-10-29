package dev.zakocoder.app.ai

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Bounded, deterministic project discovery. Blocking IO; no process, credential or generated-tree reads. */
class AiProjectIndex(
    private val files: AiProjectFiles,
    private val id: String,
    private val cancellation: CoroutineContext? = null,
    private val directoryEntries: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) },
) {
    data class Listing(val paths: List<String>, val visited: Int, val truncated: Boolean)
    data class Match(val path: String, val line: Int, val text: String)
    data class Search(val matches: List<Match>, val visited: Int, val skipped: Int, val truncated: Boolean)
    private fun check() { cancellation?.ensureActive() }

    /** Shared across the entire traversal, including excluded entries and buffered ancestors. */
    private inner class ScanBudget {
        var sampled = 0
        var truncated = false
        fun children(directory: File, directoriesFirst: Boolean): List<File> {
            check()
            if (sampled >= MAX_VISITED) { truncated = true; return emptyList() }
            val children = ArrayList<File>()
            try {
                directoryEntries(directory).use { stream ->
                    val iterator = stream.iterator()
                    while (true) {
                        check()
                        if (!iterator.hasNext()) break
                        if (sampled >= MAX_VISITED) { truncated = true; break }
                        children += iterator.next().toFile()
                        sampled++
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: IOException) { truncated = true }
            catch (_: DirectoryIteratorException) { truncated = true }
            catch (_: SecurityException) { truncated = true }
            check()
            // Only bounded samples are sorted; do not follow links even for sorting metadata.
            val comparator = if (directoriesFirst) compareByDescending<File> {
                check(); Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)
            }.thenBy { it.name } else compareBy { check(); it.name }
            children.sortWith(comparator)
            check()
            return children
        }
    }

    fun list(path: String = "", depth: Int = 2, limit: Int = 200): Listing {
        require(depth in 1..8 && limit in 1..500)
        val collected = mutableListOf<String>(); var visited = 0; var truncated = false
        val budget = ScanBudget()
        fun walk(current: String, level: Int) {
            check()
            val children = budget.children(files.resolve(id, current, true), directoriesFirst = true)
            for (child in children) {
                check()
                if (visited >= MAX_VISITED || collected.size >= limit) { truncated = true; return }
                visited++
                if (child.name in excluded) continue
                val relative = if (current.isEmpty()) child.name else "$current/${child.name}"
                val safe = try { files.resolve(id, relative) } catch (_: IllegalArgumentException) { continue }
                collected += relative + if (safe.isDirectory) "/" else ""
                if (safe.isDirectory && level < depth) walk(relative, level + 1)
            }
        }
        require(files.resolve(id, path, true).isDirectory)
        walk(path, 1)
        return Listing(collected, visited, truncated || budget.truncated)
    }

    fun search(query: String, path: String = "", glob: String = "**", caseSensitive: Boolean = true, filenames: Boolean = false, limit: Int = 80): Search {
        require(query.length <= 200 && (query.isNotEmpty() || filenames) && limit in 1..200)
        require(glob.length in 1..200 && !glob.startsWith('/') && !glob.contains('\\') && glob.split('/').none { it == ".." })
        val pattern = GlobPattern(glob, ::check)
        val matches = mutableListOf<Match>(); var visited = 0; var skipped = 0; var truncated = false; var bytes = 0L
        val budget = ScanBudget()
        fun walk(current: String, depth: Int) {
            check()
            if (depth > 16) { truncated = true; return }
            val children = budget.children(files.resolve(id, current, true), directoriesFirst = false)
            for (child in children) {
                check()
                if (visited >= MAX_VISITED || matches.size >= limit) { truncated = true; return }
                visited++
                if (child.name in excluded) continue
                val relative = if (current.isEmpty()) child.name else "$current/${child.name}"
                val safe = try { files.resolve(id, relative) } catch (_: IllegalArgumentException) { continue }
                if (safe.isDirectory) { walk(relative, depth + 1); continue }
                val local = if (path.isEmpty()) relative else relative.removePrefix("$path/")
                if (!pattern.matches(local) && !(glob.indexOf('/') < 0 && pattern.matches(child.name))) continue
                if (filenames) {
                    if (relative.contains(query, ignoreCase = !caseSensitive)) matches += Match(relative, 0, "")
                    continue
                }
                val baseline = try {
                    if (safe.length() > 1024 * 1024) { skipped++; continue }
                    if (bytes + safe.length() > 16 * 1024 * 1024) { truncated = true; return }
                    bytes += safe.length()
                    files.read(id, relative)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { skipped++; continue }
                for ((index, line) in baseline.text.lineSequence().withIndex()) {
                    check()
                    if (!line.contains(query, ignoreCase = !caseSensitive)) continue
                    if (matches.size >= limit) { truncated = true; break }
                    // Include the hit even when it is far along a long minified/generated line.
                    val offset = line.indexOf(query, ignoreCase = !caseSensitive)
                    val start = maxOf(0, offset - 100)
                    matches += Match(relative, index + 1, line.substring(start, minOf(line.length, start + 400)))
                }
            }
        }
        require(files.resolve(id, path, true).isDirectory)
        walk(path, 0)
        return Search(matches, visited, skipped, truncated || budget.truncated)
    }

    companion object {
        const val MAX_VISITED = 10000
        val excluded = setOf("node_modules", "build", ".gradle", ".cache", ".venv", "venv", "target", "dist", "__pycache__", ".next", "coverage", ".idea", ".zakocoder", "vendor")
        /** Glob wildcards only. No regex engine or backtracking, including repeated recursive wildcards. */
        fun globPattern(glob: String): GlobPattern = GlobPattern(glob)
    }

    class GlobPattern internal constructor(glob: String, private val check: () -> Unit = {}) {
        private data class Token(val kind: Int, val literal: Int = 0)
        private val tokens = buildList {
            var offset = 0
            while (offset < glob.length) {
                when {
                    glob.startsWith("**/", offset) -> { add(Token(3)); offset += 3 }
                    glob.startsWith("**", offset) -> { add(Token(2)); offset += 2 }
                    glob[offset] == '*' -> { add(Token(1)); offset++ }
                    glob[offset] == '?' -> { add(Token(4)); offset++ }
                    else -> {
                        val codePoint = glob.codePointAt(offset)
                        add(Token(0, codePoint)); offset += Character.charCount(codePoint)
                    }
                }
            }
        }

        /** O(pattern code points × input code points), O(input code points) memory. */
        fun matches(value: String): Boolean {
            check()
            val points = IntArray(value.codePointCount(0, value.length))
            var offset = 0
            for (index in points.indices) {
                check()
                points[index] = value.codePointAt(offset)
                offset += Character.charCount(points[index])
            }
            var previous = BooleanArray(points.size + 1).apply { this[0] = true }
            for (token in tokens) {
                check()
                val next = BooleanArray(points.size + 1)
                if (token.kind in 1..3) next[0] = previous[0]
                var prefix = previous[0]
                for (index in points.indices) {
                    check()
                    val point = points[index]
                    // Preserve Java regex dot's line-terminator boundary for ** and **/.
                    val dot = point != 10 && point != 13 && point != 0x85 && point != 0x2028 && point != 0x2029
                    next[index + 1] = when (token.kind) {
                        0 -> previous[index] && point == token.literal
                        1 -> previous[index + 1] || (point != 47 && next[index])
                        2 -> previous[index + 1] || (dot && next[index])
                        3 -> previous[index + 1] || (point == 47 && prefix)
                        else -> previous[index] && point != 47
                    }
                    prefix = previous[index + 1] || (dot && prefix)
                }
                previous = next
            }
            return previous[points.size]
        }
    }
}

/** One-based inclusive line range; preserves the full-file baseline separately for safe writes. */
fun fileLineRange(text: String, start: Int, end: Int): String {
    val lines = text.split('\n')
    require(start in 1..lines.size && end >= start && end - start < 1000)
    var chars = 0; var last = start - 1
    val content = buildString {
        for (index in start - 1 until minOf(end, lines.size)) {
            val line = "${index + 1}: ${lines[index]}\n"
            if (chars + line.length > 64000) break
            append(line); chars += line.length; last = index + 1
        }
    }
    return "[lines $start..$last of ${lines.size}; truncated=${last < minOf(end, lines.size)}; line numbers are display-only]\n$content"
}
