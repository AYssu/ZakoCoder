package dev.zakocoder.app.updates

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ReleaseClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()) {
    @Volatile private var activeCall: Call? = null
    fun cancel() { activeCall?.cancel() }
    suspend fun releases(): List<AppRelease> {
        val all = mutableListOf<AppRelease>()
        // Bounded pagination includes stable releases even when many previews precede them.
        for (page in 1..3) {
            val json = JSONArray(text("https://api.github.com/repos/AYssu/ZakoCoder/releases?per_page=30&page=$page", 2_000_000))
            for (i in 0 until json.length()) {
                val r = json.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val tag = r.optString("tag_name")
                if (ReleaseVersion.parse(tag) == null) continue
                val assets = r.optJSONArray("assets") ?: continue
                val parsed = (0 until assets.length()).mapNotNull { n ->
                    val a = assets.getJSONObject(n); val url = a.optString("browser_download_url")
                    if (!validAssetUrl(url, tag)) null else ReleaseAsset(a.optString("name"), url, a.optLong("size"), a.optString("digest").ifEmpty { null })
                }
                val apk = parsed.singleOrNull { it.name == "ZakoCoder-${tag.removePrefix("v")}-arm64.apk" && it.size in 1..MAX_APK } ?: continue
                val sums = parsed.singleOrNull { it.name == "SHA256SUMS.txt" && it.size in 1..65536 } ?: continue
                all += AppRelease(tag, r.optString("body").take(24000), r.optBoolean("prerelease"), apk, sums)
            }
            if (json.length() < 30) break
        }
        return all
    }
    suspend fun download(release: AppRelease, target: File, progress: (Long, Long) -> Unit) {
        val expected = releaseChecksum(text(release.sums.url, 65536), release.apk.name) ?: throw IOException("checksum")
        release.apk.digest?.takeIf { it.startsWith("sha256:") }?.let { if (it.removePrefix("sha256:") != expected) throw IOException("checksum") }
        val partial = File(target.parentFile, target.name + ".part")
        target.delete(); partial.delete()
        try {
            response(release.apk.url).use { response ->
                val body = response.body ?: throw IOException("empty")
                if (body.contentLength() >= 0 && body.contentLength() != release.apk.size) throw IOException("size")
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        count += n; if (count > release.apk.size || count > MAX_APK) throw IOException("size")
                        output.write(buffer, 0, n); digest.update(buffer, 0, n); progress(count, release.apk.size)
                    }
                } }
                if (count != release.apk.size || digest.digest().joinToString("") { "%02x".format(it) } != expected) throw IOException("checksum")
                if (!partial.renameTo(target)) throw IOException("rename")
            }
        } finally { partial.delete() }
    }
    private suspend fun text(url: String, limit: Long): String = response(url).use { response ->
        val body = response.body ?: throw IOException("empty")
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                if (output.size() + n > limit) throw IOException("large")
                output.write(buffer, 0, n)
            }
            output.toString("UTF-8")
        }
    }
    private suspend fun response(url: String): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "ZakoCoder-Android-Updates").build())
        activeCall = call
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) { response.close(); return }
                if (!response.request.url.isHttps) { response.close(); cont.resumeWithException(IOException("insecure redirect")); return }
                if (!response.isSuccessful) { response.close(); cont.resumeWithException(IOException("HTTP ${response.code}")) }
                else cont.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    companion object {
        private const val MAX_APK = 300_000_000L
        internal fun validAssetUrl(raw: String, tag: String): Boolean {
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
                uri.path.startsWith("/AYssu/ZakoCoder/releases/download/$tag/") && uri.query == null && uri.fragment == null
        }
    }
}
