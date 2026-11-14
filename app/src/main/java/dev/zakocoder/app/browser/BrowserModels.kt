package dev.zakocoder.app.browser

internal data class BrowserState(
    val attached: Boolean = false,
    val foreground: Boolean = false,
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val busy: Boolean = false,
    val action: String = "",
    val error: String? = null,
    val pendingOrigin: String? = null,
    val pendingOriginRequestId: String? = null,
    val shareImages: Boolean = false,
)

internal class BrowserFailure(val code: String) : IllegalStateException(code)

internal fun browserErrorMessage(context: android.content.Context, code: String): String = context.getString(when (code) {
        "BROWSER_IMAGE_SHARING_DISABLED" -> dev.zakocoder.app.R.string.browser_error_image_sharing
        "BROWSER_NOT_OPEN", "BROWSER_NOT_FOREGROUND" -> dev.zakocoder.app.R.string.browser_error_not_open
        "BROWSER_INVALID_URL", "BROWSER_UNSUPPORTED_SCHEME" -> dev.zakocoder.app.R.string.browser_error_url
        "BROWSER_ORIGIN_DENIED", "BROWSER_ORIGIN_NOT_ALLOWED" -> dev.zakocoder.app.R.string.browser_error_origin
        "BROWSER_TIMEOUT" -> dev.zakocoder.app.R.string.browser_error_timeout
        "BROWSER_TLS_ERROR" -> dev.zakocoder.app.R.string.browser_error_tls
        "BROWSER_ELEMENT_NOT_FOUND" -> dev.zakocoder.app.R.string.browser_error_element
        "BROWSER_AMBIGUOUS_SELECTOR" -> dev.zakocoder.app.R.string.browser_error_ambiguous
        "BROWSER_INVALID_SELECTOR" -> dev.zakocoder.app.R.string.browser_error_selector
        "BROWSER_DOWNLOAD_UNSUPPORTED", "BROWSER_INPUT_UNSUPPORTED", "BROWSER_ACTION_UNSUPPORTED" -> dev.zakocoder.app.R.string.browser_error_unsupported
        "BROWSER_RENDERER_GONE" -> dev.zakocoder.app.R.string.browser_error_renderer
        "BROWSER_HTTP_ERROR" -> dev.zakocoder.app.R.string.browser_error_http
        "BROWSER_NOT_READY" -> dev.zakocoder.app.R.string.browser_error_not_ready
        else -> dev.zakocoder.app.R.string.browser_error_generic
    })
