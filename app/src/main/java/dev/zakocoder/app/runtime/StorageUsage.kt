package dev.zakocoder.app.runtime

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Logical size of app-owned regular files. Never follows guest or project symlinks. */
object StorageUsage {
    suspend fun measure(vararg roots: File): Long {
        val context = currentCoroutineContext()
        var bytes = 0L
        roots.forEach { root ->
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (attrs.isRegularFile) bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return bytes
    }
    fun format(bytes: Long): String = if (bytes >= 1_000_000_000L)
        String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    else String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
}
