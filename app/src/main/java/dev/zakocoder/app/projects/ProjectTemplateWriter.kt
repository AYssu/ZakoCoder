package dev.zakocoder.app.projects

import dev.zakocoder.app.runtime.PrivateFiles
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Materializes the pure [ProjectTemplates] output into a freshly created project directory.
 *
 * Pure JVM on purpose: no Android/`Context` dependency, so the scaffold contract and the rollback
 * semantics are covered by [ProjectTemplateWriterTest]. [ProjectTemplates] stays the single source
 * of truth for the limits and for the relative-path rules; this writer only enforces them on disk.
 *
 * The writer owns the transaction: any exception while validating or writing deletes the whole
 * `root` directory and rethrows, so callers ([ProjectRepository.create]) never leave a half-written
 * project behind. If the cleanup itself fails, that failure is attached as a suppressed exception
 * instead of masking the original one.
 */
object ProjectTemplateWriter {
    /** Writes [files] (project-relative path to UTF-8 text) under [root]. */
    fun write(root: File, files: Map<String, String>) {
        try {
            require(files.size <= ProjectTemplates.MAX_FILES) {
                "Template exceeds the file limit: ${files.size} > ${ProjectTemplates.MAX_FILES}"
            }
            val realRoot = root.toPath().toRealPath()
            files.forEach { (relative, content) ->
                require(ProjectTemplates.isSafeRelativePath(relative)) {
                    "Template path is not a safe relative path: $relative"
                }
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= ProjectTemplates.MAX_FILE_BYTES) {
                    "Template file exceeds the size limit: $relative (${bytes.size} > ${ProjectTemplates.MAX_FILE_BYTES})"
                }
                val target = resolveInside(realRoot, relative)
                Files.createDirectories(requireNotNull(target.parent))
                Files.write(target, bytes)
            }
        } catch (error: Exception) {
            rollback(root, error)
            throw error
        }
    }

    /**
     * Resolves every path segment against [realRoot], following symlinks on segments that already
     * exist (`toRealPath`, not `File.canonicalFile`: on Windows the latter does not resolve
     * directory symlinks). Rejects a target that leaves [realRoot] or resolves back to it.
     */
    private fun resolveInside(realRoot: Path, relative: String): Path {
        var current = realRoot
        val segments = relative.split('/')
        segments.forEachIndexed { index, segment ->
            val candidate = current.resolve(segment)
            current = if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) candidate.toRealPath() else candidate
            require(current.startsWith(realRoot)) { "Template path escapes the project directory: $relative" }
            require(index < segments.lastIndex || current != realRoot) {
                "Template path escapes the project directory: $relative"
            }
        }
        return current
    }

    private fun rollback(root: File, error: Exception) {
        try {
            // Remove link entries themselves, never recurse into their targets.
            PrivateFiles.deleteTree(root)
        } catch (cleanup: Exception) {
            error.addSuppressed(cleanup)
        }
        if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            error.addSuppressed(IOException("Failed to roll back the project directory: ${root.absolutePath}"))
        }
    }
}
