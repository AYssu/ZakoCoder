package dev.zakocoder.app.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Extract files before creating links, so archive-controlled links cannot redirect writes. */
object ArchiveExtractor {
    fun entryPath(root: Path, name: String, prefix: String): Path {
        require(name.startsWith(prefix)) { "Unexpected archive prefix" }
        val relative = name.removePrefix(prefix)
        require(!relative.startsWith('/') && '\u0000' !in relative) { "Invalid archive path" }
        val result = root.resolve(relative).normalize()
        require(result.startsWith(root)) { "Archive path escapes installation" }
        return result
    }

    fun extract(archive: File, destination: File, prefix: String, cancelled: () -> Unit, progress: (Int) -> Unit) {
        val root = destination.canonicalFile.toPath()
        Files.createDirectories(root)
        val links = mutableListOf<Pair<Path, String>>()
        var entries = 0
        var bytes = 0L
        XZCompressorInputStream(archive.inputStream().buffered()).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    cancelled()
                    val entry = tar.nextEntry ?: break
                    val target = entryPath(root, entry.name, prefix)
                    require(++entries <= 50000) { "Archive entry limit exceeded" }
                    when {
                        entry.isDirectory -> Files.createDirectories(target)
                        entry.isSymbolicLink -> {
                            // Linux rootfs links refer to the guest root. Rewrite absolute
                            // guest paths to relative paths inside the installation. /dev/fd
                            // is supplied by the explicit /dev binding, not extracted.
                            if (target.startsWith(root.resolve("dev"))) continue
                            val linkTarget = if (entry.linkName.startsWith('/')) {
                                root.resolve(entry.linkName.removePrefix("/")).normalize()
                            } else target.parent.resolve(entry.linkName).normalize()
                            require(linkTarget.startsWith(root)) { "Symbolic link escapes installation" }
                            links += target to target.parent.relativize(linkTarget).toString()
                        }
                        entry.isFile -> {
                            require(entry.size >= 0 && entry.size <= 256L * 1024 * 1024)
                            bytes += entry.size
                            require(bytes <= 700L * 1024 * 1024) { "Archive size limit exceeded" }
                            Files.createDirectories(target.parent)
                            require(!Files.isSymbolicLink(target))
                            target.toFile().outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    cancelled()
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                            check(target.toFile().setExecutable(entry.mode and 73 != 0, true))
                        }
                        entry.isCharacterDevice && target.startsWith(root.resolve("dev")) -> Unit
                        else -> error("Unsupported archive entry type: ${entry.name}")
                    }
                    if (entries % 200 == 0) progress(entries)
                }
            }
        }
        links.forEach { (path, target) ->
            cancelled()
            Files.createDirectories(path.parent)
            val actualParent = path.parent.toRealPath()
            require(actualParent.startsWith(root)) { "Archive link parent escapes installation" }
            val logicalTarget = path.parent.resolve(target).normalize()
            require(logicalTarget.startsWith(root)) { "Archive link target escapes installation" }
            require(!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Duplicate archive link" }
            val relativeTarget = actualParent.relativize(logicalTarget).toString().ifEmpty { "." }
            Files.createSymbolicLink(path, java.nio.file.Paths.get(relativeTarget))
        }
    }
}
