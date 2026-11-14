package dev.zakocoder.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.os.Message
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.zakocoder.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.min

/** One visible native browser. No JavaScript bridge, implicit intents, downloads or file access. */
internal class BrowserController(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val mutable = MutableStateFlow(BrowserState())
    val state = mutable.asStateFlow()
    private var view: WebView? = null
    private var generation = 0L
    private var activeOperation: Job? = null
    private class OperationAccess(val job: Job, val permitsExternalNavigation: () -> Boolean)
    @Volatile private var operationAccess: OperationAccess? = null
    private var stopEpoch = 0L
    private val requestedOperations = mutableSetOf<Job>()
    private var interceptedNavigation: BrowserPolicy.Address? = null
    private var suppressLoads = false
    private var loadStarted = 0L
    private var loadFinished = 0L
    private var startedUrl = ""
    private var sameDocumentDestination: String? = null
    private val mutex = Mutex()
    private val allowedOrigins = ConcurrentHashMap.newKeySet<String>()
    private class OriginPermission(val id: String, val origin: String, val reply: CompletableDeferred<Boolean>)
    private var permission: OriginPermission? = null
    private var domScript: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        check(view == null || view === webView) { "A browser is already attached" }
        if (view === webView) return
        generation++
        view = webView
        allowedOrigins.clear()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            safeBrowsingEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = true
            displayZoomControls = false
        }
        // Never forward content to an installed app, system chooser, file picker or host bridge.
        webView.setDownloadListener { _, _, _, _, _ -> mutable.update { it.copy(error = "BROWSER_DOWNLOAD_UNSUPPORTED") } }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, progress: Int) {
                if (view === v) mutable.update { it.copy(progress = progress.coerceIn(0, 100)) }
            }
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onShowFileChooser(v: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null); return true
            }
            override fun onCreateWindow(v: WebView?, dialog: Boolean, gesture: Boolean, resultMsg: Message?): Boolean = false
            override fun onReceivedTitle(v: WebView?, title: String?) {
                if (view === v) mutable.update { it.copy(title = title.orEmpty().take(256)) }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                if (v !== view || request == null) return true
                if (!request.isForMainFrame) return !permittedScheme(request.url.toString())
                val address = runCatching { BrowserPolicy.address(request.url.toString()) }.getOrNull()
                if (address == null) { mutable.update { it.copy(error = "BROWSER_UNSUPPORTED_SCHEME") }; return true }
                if (suppressLoads) {
                    if (request.hasGesture()) requestNavigation(address.url)
                    return true
                }
                if (isAllowed(address)) return false
                // The current operation owns redirects and dynamic click navigation. Never queue
                // an independent task behind its mutex: it could run after that operation failed.
                if (activeOperation?.isActive == true) {
                    if (request.method.equals("GET", true) && interceptedNavigation == null) interceptedNavigation = address
                    else mutable.update { it.copy(error = "BROWSER_ORIGIN_NOT_ALLOWED") }
                } else if (request.hasGesture() && request.method.equals("GET", true)) {
                    requestNavigation(address.url)
                } else mutable.update { it.copy(error = "BROWSER_ORIGIN_NOT_ALLOWED") }
                return true
            }
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                if (!permittedScheme(request.url.toString())) return blockedResponse()
                // A redirect cannot silently acquire a new top-level origin.
                if (request.isForMainFrame) {
                    val target = runCatching { BrowserPolicy.address(request.url.toString()) }.getOrNull()
                    if (target == null || !isAllowed(target)) return blockedResponse()
                }
                return null
            }
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                if (v !== view) return
                val target = runCatching { BrowserPolicy.address(url.orEmpty()) }.getOrNull()
                if (suppressLoads || target == null || !isAllowed(target)) {
                    v?.stopLoading()
                    if (!suppressLoads) mutable.update { it.copy(loading = false, error = "BROWSER_ORIGIN_NOT_ALLOWED") }
                    return
                }
                sameDocumentDestination = null
                loadStarted++
                startedUrl = target.url
                mutable.update { it.copy(url = target.url, loading = true, progress = 0, error = null) }
            }
            override fun doUpdateVisitedHistory(v: WebView?, url: String?, isReload: Boolean) {
                if (v !== view || suppressLoads || isReload) return
                val updated = runCatching { BrowserPolicy.address(url.orEmpty()).url }.getOrNull()
                if (updated != null && updated == sameDocumentDestination) {
                    // Fragment history moves do not produce onPageStarted/onPageFinished.
                    sameDocumentDestination = null
                    loadStarted++
                    loadFinished = loadStarted
                    startedUrl = updated
                    mutable.update { it.copy(url = updated, loading = false, canGoBack = v?.canGoBack() == true) }
                }
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                if (v !== view || suppressLoads) return
                val finishedUrl = runCatching { BrowserPolicy.address(url.orEmpty()).url }.getOrNull()
                // stopLoading()/a previous document can finish after a fresh load was requested.
                if (finishedUrl != startedUrl) return
                loadFinished = loadStarted
                mutable.update { it.copy(loading = false, canGoBack = v?.canGoBack() == true) }
            }
            override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (view === v && !suppressLoads && request?.isForMainFrame == true) mutable.update { it.copy(loading = false, error = "BROWSER_LOAD_FAILED") }
            }
            override fun onReceivedHttpError(v: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                if (view === v && !suppressLoads && request?.isForMainFrame == true) mutable.update { it.copy(error = "BROWSER_HTTP_ERROR", loading = false) }
            }
            override fun onReceivedSslError(v: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                if (view === v && !suppressLoads) mutable.update { it.copy(error = "BROWSER_TLS_ERROR", loading = false) }
            }
            override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (view === v && v != null) { detach(v); mutable.update { it.copy(error = "BROWSER_RENDERER_GONE") } }
                return true
            }
        }
        mutable.value = BrowserState(attached = true)
    }

    /** Called by the page lifecycle; background pages cannot receive AI or user operations. */
    fun setForeground(foreground: Boolean) {
        mutable.update { it.copy(foreground = foreground) }
        if (foreground) view?.onResume() else { view?.onPause(); stop() }
    }

    fun detach(webView: WebView) {
        if (view !== webView) return
        stop()
        view = null
        generation++
        allowedOrigins.clear()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
        mutable.update { it.copy(attached = false, foreground = false, loading = false, busy = false, pendingOrigin = null, pendingOriginRequestId = null) }
    }

    /** Main-thread identity check and completion are one operation; stale buttons are inert. */
    fun confirmOrigin(origin: String, requestId: String, allow: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = permission ?: return false
        if (current.id != requestId || current.origin != origin ||
            mutable.value.pendingOriginRequestId != requestId || mutable.value.pendingOrigin != origin) return false
        return current.reply.complete(allow)
    }

    suspend fun waitUntilAttached(timeoutMillis: Long = 10_000) = withContext(Dispatchers.Main.immediate) {
        try {
            withTimeout(timeoutMillis.coerceIn(500, 30_000)) {
                while (!state.value.attached || !state.value.foreground || view?.isAttachedToWindow != true) delay(30)
            }
        } catch (_: TimeoutCancellationException) { throw BrowserFailure("BROWSER_NOT_OPEN") }
    }

    /** Capability shutdown; page disposal can safely call detach again. Main thread only. */
    fun close() {
        view?.let(::detach) ?: stop()
        mutable.update { it.copy(shareImages = false) }
    }

    fun setShareImages(enabled: Boolean) { mutable.update { it.copy(shareImages = enabled) } }


    fun requestNavigation(url: String) = requestOperation("navigate", JSONObject().put("url", url))

    fun requestBack() = requestOperation("back", JSONObject())

    private fun requestOperation(action: String, args: JSONObject) {
        // A deliberate user navigation supersedes the old action rather than waiting invisibly.
        stop()
        val expectedEpoch = stopEpoch
        val job = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            try {
                if (expectedEpoch != stopEpoch) return@launch
                execute(action, args)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = (e as? BrowserFailure)?.code ?: "BROWSER_LOAD_FAILED") } }
        }
        requestedOperations.add(job)
        job.invokeOnCompletion { requestedOperations.remove(job) }
        job.start()
    }

    fun requestReload() {
        val url = mutable.value.url
        if (url.isNotBlank()) requestNavigation(url)
    }

    fun stop() {
        stopEpoch++
        requestedOperations.toList().forEach { it.cancel(CancellationException("Browser stopped")) }
        requestedOperations.clear()
        activeOperation?.cancel(CancellationException("Browser stopped"))
        stopCurrentLoad()
    }

    private fun stopCurrentLoad() {
        suppressLoads = true
        interceptedNavigation = null
        sameDocumentDestination = null
        permission?.reply?.complete(false)
        permission = null
        view?.stopLoading()
        mutable.update { it.copy(loading = false, pendingOrigin = null, pendingOriginRequestId = null) }
    }

    /** Results distinguish command dispatch from observed postconditions. Call only after tool approval. */
    suspend fun execute(action: String, args: JSONObject,
        externalNavigationAllowed: () -> Boolean = { false }): JSONObject = withContext(Dispatchers.Main.immediate) {
        // Observation must not open a browser, wait for its mutex, clear errors or alter a task.
        if (action == "status") return@withContext snapshot()
        val expectedEpoch = stopEpoch
        mutex.withLock {
            if (expectedEpoch != stopEpoch) throw CancellationException("Browser action was stopped while queued")
            coroutineScope {
                val current = currentCoroutineContext()[Job]
                activeOperation = current
                // The callback must read live task/policy ownership, never a captured approval bit.
                // It applies only while this tool is executing and is never added to allowedOrigins.
                operationAccess = current?.let { OperationAccess(it, externalNavigationAllowed) }
                val expected = generation
                interceptedNavigation = null
                mutable.update { it.copy(busy = true, action = action, error = null) }
                try {
                    val data = withTimeout(timeout(action, args)) {
                        requireView(expected)
                        when (action) {
                            "navigate" -> navigate(args.optString("url"), expected)
                            "back" -> {
                                val v = requireView(expected)
                                if (!v.canGoBack()) throw BrowserFailure("BROWSER_NO_HISTORY")
                                val history = v.copyBackForwardList()
                                val target = checkedAddress(history.getItemAtIndex(history.currentIndex - 1)?.url.orEmpty())
                                authorize(target, expected)
                                val minimumStart = beginLoad(target)
                                v.goBack()
                                awaitLoaded(expected, minimumStart)
                                JSONObject().put("dispatched", true).put("verified", true)
                            }
                            "screenshot" -> screenshot(expected)
                            "wait" -> {
                                validateArguments(action, args)
                                awaitCondition(args, expected).put("verified", true)
                            }
                            "read", "find", "click", "type", "scroll" -> {
                                validateArguments(action, args)
                                var clickTarget: BrowserPolicy.Address? = null
                                if (action == "click") {
                                    // Resolve link/form destinations before one and only one click.
                                    // Inspecting has no side effects; granting an origin never retries a click.
                                    val inspection = dom("inspect_click", args, expected)
                                    val raw = inspection.optString("navigation_url")
                                    if (raw.isNotEmpty()) {
                                        clickTarget = checkedAddress(raw)
                                        authorize(clickTarget, expected)
                                    }
                                }
                                if (action in setOf("click", "type", "scroll")) suppressLoads = false
                                val minimumStart = loadStarted + 1
                                val previousUrl = mutable.value.url
                                val result = dom(action, args, expected)
                                if (action == "click" && clickTarget != null &&
                                    clickTarget.url.substringBefore('#') != previousUrl.substringBefore('#')) {
                                    // The dispatch result alone is not evidence that navigation finished.
                                    awaitLoaded(expected, minimumStart)
                                }
                                if (action == "click" && (args.has("wait_selector") || args.has("wait_text"))) {
                                    val check = JSONObject()
                                    if (args.has("wait_selector")) check.put("selector", args.getString("wait_selector"))
                                    if (args.has("wait_text")) check.put("text", args.getString("wait_text"))
                                    awaitCondition(check, expected)
                                    result.put("verified", true)
                                } else if (action == "read" || action == "find") result.put("verified", true)
                                result
                            }
                            else -> throw BrowserFailure("BROWSER_ACTION_UNSUPPORTED")
                        }
                    }
                    data.put("action", action).put("url", mutable.value.url).put("status", if (data.optBoolean("verified")) "verified" else "dispatched")
                } catch (_: TimeoutCancellationException) {
                    if (activeOperation === current && generation == expected) stopCurrentLoad()
                    mutable.update { it.copy(error = "BROWSER_TIMEOUT") }
                    throw BrowserFailure("BROWSER_TIMEOUT")
                } catch (e: CancellationException) {
                    // Cancellation owns the current document load as well as its suspended JS.
                    if (activeOperation === current && generation == expected) stopCurrentLoad()
                    throw e
                } catch (e: Exception) {
                    if (activeOperation === current && generation == expected) stopCurrentLoad()
                    val code = (e as? BrowserFailure)?.code ?: "BROWSER_OPERATION_FAILED"
                    mutable.update { it.copy(error = code) }
                    throw BrowserFailure(code)
                } finally {
                    if (activeOperation === current) {
                        operationAccess = null
                        activeOperation = null
                        interceptedNavigation = null
                        permission?.reply?.complete(false)
                        permission = null
                        mutable.update { it.copy(busy = false, action = "", pendingOrigin = null, pendingOriginRequestId = null) }
                    }
                }
            }
        }
    }

    private fun snapshot(): JSONObject {
        val current = mutable.value
        return JSONObject().put("action", "status").put("status", "verified").put("verified", true)
            .put("dispatched", false).put("attached", current.attached)
            .put("foreground", current.foreground).put("available", current.attached && current.foreground)
            .put("url", current.url).put("title", current.title).put("loading", current.loading)
            .put("busy", current.busy).put("current_action", current.action).put("progress", current.progress)
            .put("can_go_back", current.canGoBack).put("share_images", current.shareImages)
            .put("pending_origin", current.pendingOrigin ?: JSONObject.NULL).put("error", current.error ?: JSONObject.NULL)
    }

    /** Set loading before dispatch: goBack/loadUrl callbacks are asynchronous. */
    private fun beginLoad(address: BrowserPolicy.Address): Long {
        suppressLoads = false
        val previous = mutable.value.url
        sameDocumentDestination = address.url.takeIf {
            it != previous && it.substringBefore('#') == previous.substringBefore('#')
        }
        mutable.update { it.copy(loading = true, progress = 0, error = null, url = address.url) }
        return loadStarted + 1
    }

    private suspend fun navigate(raw: String, expected: Long): JSONObject {
        val address = checkedAddress(raw)
        authorize(address, expected)
        val v = requireView(expected)
        val minimumStart = beginLoad(address)
        v.loadUrl(address.url)
        awaitLoaded(expected, minimumStart)
        return JSONObject().put("dispatched", true).put("verified", true).put("verification", "main_frame_load_finished")
    }

    private suspend fun authorize(address: BrowserPolicy.Address, expected: Long) {
        if (isAllowed(address)) return
        val request = OriginPermission(UUID.randomUUID().toString(), address.origin, CompletableDeferred())
        permission = request
        mutable.update { it.copy(pendingOrigin = request.origin, pendingOriginRequestId = request.id) }
        try {
            if (!request.reply.await()) throw BrowserFailure("BROWSER_ORIGIN_DENIED")
            requireView(expected)
            allowedOrigins.add(request.origin)
        } finally {
            if (permission === request) {
                permission = null
                mutable.update { it.copy(pendingOrigin = null, pendingOriginRequestId = null) }
            }
        }
    }

    private suspend fun awaitLoaded(expected: Long, minimumStart: Long = loadStarted) {
        var requiredStart = minimumStart
        var redirects = 0
        while (true) {
            requireView(expected)
            currentCoroutineContext().ensureActive()
            val redirected = interceptedNavigation
            if (redirected != null) {
                interceptedNavigation = null
                if (++redirects > 8) throw BrowserFailure("BROWSER_LOAD_FAILED")
                authorize(redirected, expected)
                requiredStart = beginLoad(redirected)
                requireView(expected).loadUrl(redirected.url)
            }
            mutable.value.error?.let { throw BrowserFailure(it) }
            if (!mutable.value.loading && loadStarted >= requiredStart && loadFinished >= loadStarted) return
            delay(40)
        }
    }

    private suspend fun awaitCondition(args: JSONObject, expected: Long): JSONObject {
        while (true) {
            // Redirect consent belongs to this exact operation, including a dynamic onclick.
            if (interceptedNavigation != null || mutable.value.loading) awaitLoaded(expected)
            mutable.value.error?.let { throw BrowserFailure(it) }
            val result = dom("wait", args, expected)
            if (result.optBoolean("matched") && interceptedNavigation == null && !mutable.value.loading) return result
            delay(80)
        }
    }

    private suspend fun dom(action: String, args: JSONObject, expected: Long): JSONObject {
        val v = requireView(expected)
        if (mutable.value.url.isBlank()) throw BrowserFailure("BROWSER_NOT_READY")
        val source = domScript ?: withContext(Dispatchers.IO) {
            context.assets.open("browser/agent-dom.js").bufferedReader().use { it.readText() }
        }.also { domScript = it }
        requireView(expected)
        val script = "$source(${JSONObject.quote(action)},$args)"
        val json = suspendCancellableCoroutine<String> { continuation ->
            v.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value ?: "null") }
        }
        currentCoroutineContext().ensureActive()
        requireView(expected)
        val result = runCatching { JSONTokener(json).nextValue() as? JSONObject }.getOrNull()
            ?: throw BrowserFailure("BROWSER_DOM_FAILED")
        if (!result.optBoolean("ok")) throw BrowserFailure(result.optString("error", "BROWSER_DOM_FAILED"))
        return result
    }

    private suspend fun screenshot(expected: Long): JSONObject {
        val v = requireView(expected)
        if (!mutable.value.shareImages) throw BrowserFailure("BROWSER_IMAGE_SHARING_DISABLED")
        if (v.width <= 0 || v.height <= 0 || mutable.value.url.isBlank()) throw BrowserFailure("BROWSER_NOT_READY")
        val scale = min(1f, 1440f / maxOf(v.width, v.height))
        val bitmap = createBitmap((v.width * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        v.draw(canvas)
        return try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "agent-browser").apply { mkdirs() }
                // Bounded diagnostic storage, never retain more than 8 old captures.
                dir.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
                File(dir, "viewport-${UUID.randomUUID()}.png").also { target ->
                    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }
            requireView(expected)
            JSONObject().put("verified", true).put("image_path", file.absolutePath).put("mime_type", "image/png")
                .put("width", bitmap.width).put("height", bitmap.height).put("scope", "visible_viewport")
        } finally { bitmap.recycle() }
    }

    private fun requireView(expected: Long): WebView {
        val v = view ?: throw BrowserFailure("BROWSER_NOT_OPEN")
        if (expected != generation || !v.isAttachedToWindow || !mutable.value.foreground) throw BrowserFailure("BROWSER_NOT_FOREGROUND")
        return v
    }
    private fun checkedAddress(raw: String): BrowserPolicy.Address = try { BrowserPolicy.address(raw) }
        catch (e: IllegalArgumentException) { throw BrowserFailure(e.message ?: "BROWSER_INVALID_URL") }
    private fun isAllowed(address: BrowserPolicy.Address): Boolean = address.local || address.origin in allowedOrigins ||
        operationAccess?.let { it.job.isActive && it.permitsExternalNavigation() } == true
    private fun permittedScheme(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("data:", true) || url.startsWith("blob:", true)
    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun timeout(action: String, args: JSONObject): Long = if (action == "navigate") 120_000 else args.optLong("timeout_ms", 15_000).coerceIn(500, 30_000)
    private fun validateArguments(action: String, args: JSONObject) {
        listOf("selector", "wait_selector").filter(args::has).forEach {
            if (args.optString(it).length !in 1..1024) throw BrowserFailure("BROWSER_INVALID_SELECTOR")
        }
        if (action in setOf("click", "type") && !args.has("selector")) throw BrowserFailure("BROWSER_INVALID_SELECTOR")
        if (action in setOf("find", "type") && (!args.has("text") || args.optString("text").length > 16000)) throw BrowserFailure("BROWSER_TEXT_REQUIRED")
        listOf("text", "wait_text").filter(args::has).forEach { if (args.optString(it).length > 16000) throw BrowserFailure("BROWSER_TEXT_REQUIRED") }
        if (action == "wait" && !args.has("selector") && args.optString("text").isBlank()) throw BrowserFailure("BROWSER_WAIT_CONDITION_REQUIRED")
        if (action == "scroll") listOf("x", "y").forEach { key ->
            val value = args.optDouble(key, 0.0)
            if (!value.isFinite() || kotlin.math.abs(value) > 10000) throw BrowserFailure("BROWSER_INVALID_SCROLL")
        }
    }

    fun errorMessage(code: String): String = browserErrorMessage(context, code)
}
