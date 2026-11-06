package dev.zakocoder.app.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Copy the pinned image before the existing installer transaction; never publish a partial archive. */
internal object BundledRootfsArchive {
    fun copyVerified(input: InputStream, archive: File, expectedBytes: Long, expectedSha256: String,
        invalidImage: String, checkCancelled: () -> Unit, progress: (Int) -> Unit) {
        check(expectedBytes > 0) { invalidImage }
        val partial = File(archive.parentFile, "bundled.partial")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var previousPercent = -1
            partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= expectedBytes) { invalidImage }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    val percent = (bytes * 100 / expectedBytes).toInt()
                    if (percent != previousPercent) { progress(percent); previousPercent = percent }
                }
                output.fd.sync()
            }
            checkCancelled()
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            check(bytes == expectedBytes && checksum == expectedSha256) { invalidImage }
            Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(partial.toPath()) }
    }
}
