package dev.zakocoder.app.projects

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import dev.zakocoder.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Only an explicit user action can prepare an APK for the system installer. */
class ProjectApkInstaller(context: Context, private val projects: ProjectRepository) {
    private val context = context.applicationContext
    fun needsSourcePermission(): Boolean = !context.packageManager.canRequestPackageInstalls()
    fun sourcePermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri())

    suspend fun prepare(projectId: String, relative: String): Intent = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val file = try {
            ApkInstallCopy(File(context.cacheDir, "apk-install")).prepare(projects.directory(projectId), relative) {
                coroutine.ensureActive()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { throw IllegalStateException(context.getString(R.string.apk_prepare_failed), error) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apk-files", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                clipData = ClipData.newRawUri("", uri)
            }
    }
}

/** Immutable private snapshot; never exposes the project directory to another application. */
internal class ApkInstallCopy(private val cache: File, private val maxBytes: Long = 512L * 1024 * 1024) {
    fun prepare(root: File, relative: String, checkCancelled: () -> Unit = {}): File {
        require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative)
        val parts = relative.split('/')
        require(parts.all { it.isNotEmpty() && it !in setOf(".", "..") && it.none(Char::isISOControl) })
        require(parts.last().endsWith(".apk", ignoreCase = true))
        require(!Files.isSymbolicLink(root.toPath()) && root.isDirectory)
        val boundary = root.canonicalFile.toPath()
        var source = root
        for (part in parts) {
            source = File(source, part)
            require(!Files.isSymbolicLink(source.toPath()))
            require(source.canonicalFile.toPath().startsWith(boundary))
        }
        require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) && source.length() in 1..maxBytes)
        Files.createDirectories(cache.toPath())
        require(!Files.isSymbolicLink(cache.toPath()) && cache.isDirectory)
        // Keep recent snapshots available while the external installer reads them.
        cache.listFiles().orEmpty().forEach { file ->
            if (!Files.isSymbolicLink(file.toPath()) && file.isFile &&
                file.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000) Files.deleteIfExists(file.toPath())
        }
        require(cache.listFiles().orEmpty().sumOf { if (Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) it.length() else 0 } + source.length() <= 2 * maxBytes)
        val output = File(cache, "${UUID.randomUUID()}.apk")
        try {
            Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(output.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { stream ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes)
                        stream.write(buffer, 0, count)
                    }
                    require(total > 0)
                }
            }
            checkCancelled()
            var verified = root
            for (part in parts) {
                verified = File(verified, part)
                require(!Files.isSymbolicLink(verified.toPath()) && verified.canonicalFile.toPath().startsWith(boundary))
            }
            return output
        } catch (error: Exception) {
            try { Files.deleteIfExists(output.toPath()) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
