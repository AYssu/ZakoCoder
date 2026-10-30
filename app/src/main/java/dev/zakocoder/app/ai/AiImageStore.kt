package dev.zakocoder.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlin.math.roundToInt

/** Owns only normalized JPEGs in a private archive. No retained SAF grants or age-based eviction. */
class AiImageStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "ai-images-v1")
    // The application normally owns one store; rebuilding a controller must not race archive caps.
    private companion object { val mutex = Mutex() }

    suspend fun import(uri: Uri): AiImageAttachment = imageIo {
        require(uri.scheme == "content")
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val source = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > AiImages.MAX_SOURCE_BYTES - output.size()) throw AiImageException(AiImageException.Reason.TooLarge)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }.orEmpty().filterNot(Char::isISOControl).take(120).ifBlank { "image.jpg" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 32768 && bounds.outHeight <= 32768)
            if (bounds.outWidth.toLong() * bounds.outHeight > AiImages.MAX_SOURCE_PIXELS) throw AiImageException(AiImageException.Reason.TooLarge)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > AiImages.MAX_EDGE * 2) sample *= 2
            currentCoroutineContext().ensureActive()
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
            var normalized: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                val matrix = orientation(source)
                val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                try {
                    val ratio = minOf(1.0, AiImages.MAX_EDGE.toDouble() / maxOf(oriented.width, oriented.height))
                    val width = (oriented.width * ratio).roundToInt().coerceAtLeast(1)
                    val height = (oriented.height * ratio).roundToInt().coerceAtLeast(1)
                    normalized = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(requireNotNull(normalized)).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(oriented, null, android.graphics.Rect(0, 0, width, height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    }
                } finally { if (oriented !== decoded) oriented.recycle() }
                val bitmap = requireNotNull(normalized)
                var jpeg: ByteArray? = null
                for (quality in listOf(90, 75, 60, 45, 30, 15)) {
                    currentCoroutineContext().ensureActive()
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    if (output.size() <= AiImages.MAX_IMAGE_BYTES) { jpeg = output.toByteArray(); break }
                }
                val bytes = requireNotNull(jpeg)
                currentCoroutineContext().ensureActive()
                val root = root()
                val files = requireNotNull(root.listFiles())
                if (files.size >= AiImages.MAX_STORED_IMAGES || files.sumOf { it.length() } + bytes.size > AiImages.MAX_STORED_BYTES)
                    throw AiImageException(AiImageException.Reason.Capacity)
                val image = AiImageAttachment(UUID.randomUUID().toString(), name, "image/jpeg", bitmap.width, bitmap.height, bytes.size)
                val atomic = AtomicFile(file(image))
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    currentCoroutineContext().ensureActive()
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
                image
            } finally { normalized?.recycle(); decoded.recycle() }
        }
    }

    suspend fun dataUrl(image: AiImageAttachment): String = imageIo {
        mutex.withLock {
            val bytes = read(image)
            currentCoroutineContext().ensureActive()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /** Caller owns the returned bitmap and must recycle it once its preview leaves composition. */
    suspend fun thumbnail(image: AiImageAttachment): Bitmap = imageIo {
        mutex.withLock {
            val bytes = read(image)
            var sample = 1
            while (maxOf(image.width, image.height) / sample > 640) sample *= 2
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
            var result: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                val ratio = minOf(1.0, 320.0 / maxOf(decoded.width, decoded.height))
                result = if (ratio == 1.0) decoded else decoded.scale(
                    (decoded.width * ratio).roundToInt().coerceAtLeast(1), (decoded.height * ratio).roundToInt().coerceAtLeast(1), true)
                currentCoroutineContext().ensureActive()
                requireNotNull(result)
            } catch (failure: Throwable) { result?.recycle(); throw failure }
            finally { if (result !== decoded) decoded.recycle() }
        }
    }

    suspend fun delete(image: AiImageAttachment) = deleteAll(listOf(image))
    suspend fun deleteAll(images: Collection<AiImageAttachment>): Unit = imageIo {
        mutex.withLock {
            images.forEach { image ->
                currentCoroutineContext().ensureActive()
                AiImages.validate(image)
                val target = file(image)
                require(!Files.isSymbolicLink(target.toPath()))
                AtomicFile(target).delete()
                check(!target.exists() && !File(target.path + ".bak").exists())
            }
        }
    }

    /** Call only with a complete successful history scan plus every pending/in-memory reference.
     * The caller serializes history mutations and attachment imports for the scan-and-prune interval.
     * Unknown files and links are retained; no external path is followed or recursively deleted.
     */
    suspend fun pruneUnreferenced(keep: Set<String>): Unit = imageIo {
        require(keep.all(AiImages::validId))
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val archive = root()
            val entries = requireNotNull(archive.listFiles())
            require(entries.size <= AiImages.MAX_STORED_IMAGES * 4)
            entries.forEach { entry ->
                currentCoroutineContext().ensureActive()
                if (!entry.name.endsWith(".jpg")) return@forEach
                val id = entry.name.removeSuffix(".jpg")
                if (!AiImages.validId(id) || id in keep || !Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)) return@forEach
                // Never discard an AtomicFile's recovery state or a path that changed to a link.
                if (Files.exists(File(entry.path + ".bak").toPath(), NOFOLLOW_LINKS) ||
                    Files.exists(File(entry.path + ".new").toPath(), NOFOLLOW_LINKS)) return@forEach
                require(entry.parentFile == archive && !Files.isSymbolicLink(entry.toPath()))
                Files.delete(entry.toPath())
            }
        }
    }

    private suspend fun read(image: AiImageAttachment): ByteArray {
        AiImages.validate(image)
        val target = file(image)
        if (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) || target.length() != image.bytes.toLong())
            throw AiImageException(AiImageException.Reason.Missing)
        val bytes = Files.newInputStream(target.toPath(), NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > AiImages.MAX_IMAGE_BYTES - output.size()) throw AiImageException(AiImageException.Reason.Missing)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size != image.bytes) throw AiImageException(AiImageException.Reason.Missing)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != image.mimeType || bounds.outWidth != image.width || bounds.outHeight != image.height)
            throw AiImageException(AiImageException.Reason.Missing)
        return bytes
    }

    private suspend fun <T> imageIo(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AiImageException) { throw failure }
        catch (_: IllegalArgumentException) { throw AiImageException(AiImageException.Reason.Invalid) }
        catch (_: IOException) { throw AiImageException(AiImageException.Reason.Storage) }
        catch (_: SecurityException) { throw AiImageException(AiImageException.Reason.Storage) }
        catch (_: IllegalStateException) { throw AiImageException(AiImageException.Reason.Storage) }
        catch (_: Exception) { throw AiImageException(AiImageException.Reason.Storage) }
        catch (_: OutOfMemoryError) { throw AiImageException(AiImageException.Reason.TooLarge) }
    }

    private fun root(): File {
        require(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdirs())
        require(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        return directory
    }
    private fun file(image: AiImageAttachment): File = File(root(), image.id + ".jpg").also {
        require(!Files.isSymbolicLink(it.toPath()) && !Files.isSymbolicLink(File(it.path + ".bak").toPath()))
    }
    private fun orientation(bytes: ByteArray): Matrix {
        val value = try { ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
        catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        return Matrix().apply {
            when (value) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
    }
}
