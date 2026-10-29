package dev.zakocoder.app.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

object PrivateFiles {
    /** Replace only an application-managed file within this root; never follow its old leaf link. */
    fun writeConfined(root: File, relative: String, text: String) {
        val boundary = root.canonicalFile.toPath()
        val requested = File(root, relative)
        val parent = requested.parentFile!!.canonicalFile
        require(parent.toPath().startsWith(boundary)) { "Configuration directory escapes runtime" }
        Files.createDirectories(parent.toPath())
        val target = File(parent, requested.name).toPath()
        require(!Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val temporary = File.createTempFile(".zakocoder-config-", ".tmp", parent)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    /** Never follows a guest-controlled symlink while cleaning our staging/cache directories. */
    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(path); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path); return FileVisitResult.CONTINUE
            }
        })
    }
}
