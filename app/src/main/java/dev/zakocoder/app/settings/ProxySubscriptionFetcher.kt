package dev.zakocoder.app.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetches raw subscription text. Implementations must not be called implicitly on page load. */
interface ProxySubscriptionFetcher {
    suspend fun fetch(url: String): String
}

/**
 * HTTPS-only subscription download with bounded cost.
 *
 * Contract:
 * - the URL must parse as HTTPS; `http://`, `file:`, malformed text and non-URL input are rejected
 *   before any socket is opened;
 * - at most [MAX_REDIRECTS] redirects are followed, each hop must stay HTTPS (a downgrade is
 *   [ProxySubscriptionError.InsecureScheme]); non-2xx response or too many hops is
 *   [ProxySubscriptionError.HttpStatus];
 * - the body is capped at 2 MiB ([ProxySubscriptionError.TooLarge]) and must be non-blank
 *   ([ProxySubscriptionError.Empty]); every transport failure maps to
 *   [ProxySubscriptionError.Unreachable];
 * - cancellation cancels the in-flight OkHttp call. The exception carries at most the category,
 *   never the URL or provider body.
 *
 * The default User-Agent is a stable application identifier; a distribution build can pass a
 * versioned value through the constructor without changing behaviour.
 */
class HttpProxySubscriptionFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : ProxySubscriptionFetcher {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        fetchFollowing(parseUrl(url), MAX_REDIRECTS)
    }

    private suspend fun fetchFollowing(url: HttpUrl, redirectsLeft: Int): String {
        val response = execute(url)
        response.use { current ->
            if (current.isSuccessful) return readBody(current)
            val location = current.header("Location")
            if (current.code !in REDIRECT_CODES || location == null) {
                throw ProxySubscriptionException(ProxySubscriptionError.HttpStatus)
            }
            val next = url.resolve(location) ?: throw ProxySubscriptionException(ProxySubscriptionError.InvalidLink)
            if (!next.isHttps) throw ProxySubscriptionException(ProxySubscriptionError.InsecureScheme)
            if (redirectsLeft <= 0) throw ProxySubscriptionException(ProxySubscriptionError.HttpStatus)
            return fetchFollowing(next, redirectsLeft - 1)
        }
    }

    private fun parseUrl(raw: String): HttpUrl {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS) throw ProxySubscriptionException(ProxySubscriptionError.InvalidLink)
        val url = trimmed.toHttpUrlOrNull() ?: throw ProxySubscriptionException(ProxySubscriptionError.InvalidLink)
        if (!url.isHttps) throw ProxySubscriptionException(ProxySubscriptionError.InsecureScheme)
        return url
    }

    private suspend fun execute(url: HttpUrl): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/plain, */*")
                .build(),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ProxySubscriptionException(ProxySubscriptionError.Unreachable, e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun readBody(response: Response): String {
        val body = response.body ?: throw ProxySubscriptionException(ProxySubscriptionError.Empty)
        if (body.contentLength() > MAX_BODY_BYTES) throw ProxySubscriptionException(ProxySubscriptionError.TooLarge)
        val source = body.source()
        // Buffer one byte past the limit so an oversized chunked response is rejected without
        // materialising the whole body.
        source.request(MAX_BODY_BYTES + 1L)
        if (source.buffer.size > MAX_BODY_BYTES) throw ProxySubscriptionException(ProxySubscriptionError.TooLarge)
        val bytes = source.readByteArray()
        if (bytes.size > MAX_BODY_BYTES) throw ProxySubscriptionException(ProxySubscriptionError.TooLarge)
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) throw ProxySubscriptionException(ProxySubscriptionError.Empty)
        return text
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "ZakoCoder"
        const val MAX_BODY_BYTES = 2 * 1024 * 1024L
        const val MAX_URL_CHARS = 2048
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
