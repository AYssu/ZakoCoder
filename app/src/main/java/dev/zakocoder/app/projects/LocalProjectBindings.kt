package dev.zakocoder.app.projects

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Path-only projects. SAF grants never participate in this mapping. Disk calls require IO. */
internal class LocalProjectBindings(private val managed: File, private val privateRoots: List<File>) {
    fun validate(path: String, existing: Collection<String> = emptyList()): File {
        require(path.isNotBlank() && path.length <= 4096 && path.none(Char::isISOControl))
        val requested = File(path)
        require(requested.isAbsolute && '\\' !in path && ':' !in path)
        require(requested.toPath().normalize() == requested.toPath())
        var ancestor: File? = requested
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor.toPath()))
            ancestor = ancestor.parentFile
        }
        val root = requested.canonicalFile
        val parent = root.parentFile
        require(parent != null && parent.parentFile != null)
        require(root.isDirectory && root.canRead() && root.canWrite() && root.canExecute())
        require(forbidden.none { inside(root, File(it)) })
        require(privateRoots.none { overlaps(root, it.canonicalFile) })
        require(!overlaps(root, managed.canonicalFile))
        val segments = root.toPath().map { it.toString() }
        require("Android" !in segments)
        // A storage volume itself is too broad: users must bind a project directory inside it.
        if (segments.firstOrNull() == "storage") {
            require(segments.size >= if (segments.getOrNull(1) == "emulated") 4 else 3)
        }
        require(existing.none { overlaps(root, File(it)) })
        return root
    }

    companion object {
        private val forbidden = listOf("/data", "/system", "/vendor", "/product", "/system_ext", "/odm", "/oem", "/apex", "/proc", "/sys", "/dev", "/acct", "/config", "/metadata", "/cache", "/root")
        fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        private fun inside(file: File, root: File) = file.toPath().startsWith(root.toPath())
        private fun overlaps(a: File, b: File) = inside(a, b) || inside(b, a)
    }
}
