package dev.zakocoder.app.settings

import android.content.Context
import androidx.core.content.edit

enum class AptSource(val baseUrl: String) {
    Ubuntu("https://ports.ubuntu.com/ubuntu-ports"),
    Tsinghua("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"),
}

enum class TerminalShell(val executable: String) { Bash("/bin/bash"), Zsh("/usr/bin/zsh") }

data class AppSettings(
    val aptSource: AptSource = AptSource.Tsinghua,
    val terminalFontSp: Int = 9,
    val terminalShell: TerminalShell = TerminalShell.Bash,
    val additionalPath: String = "",
    val dns: String = "223.5.5.5,119.29.29.29",
    val proxy: String = "",
    val editorLspEnabled: Boolean = false,
)

/**
 * Reads persisted settings without repairing them. Unknown or out-of-contract values fall back
 * in-memory only so a later save can still decide when the original preference is replaced.
 */
internal fun settingsFromStorage(stored: Map<String, *>): AppSettings {
    val defaults = AppSettings()
    val additionalPath = stored["path"] as? String
    val dns = stored["dns"] as? String
    val proxy = stored["proxy"] as? String
    return AppSettings(
        aptSource = (stored["apt"] as? String)?.let { value ->
            AptSource.entries.firstOrNull { it.name == value }
        } ?: defaults.aptSource,
        terminalFontSp = (stored["font"] as? Int)?.takeIf { it in 9..28 } ?: defaults.terminalFontSp,
        terminalShell = (stored["shell"] as? String)?.let { value ->
            TerminalShell.entries.firstOrNull { it.name == value }
        } ?: defaults.terminalShell,
        additionalPath = additionalPath?.takeIf(::validAdditionalPath) ?: defaults.additionalPath,
        dns = dns?.takeIf(::validDns) ?: defaults.dns,
        proxy = proxy?.takeIf { it.isEmpty() || runCatching { NetworkProxy.parse(it) }.isSuccess } ?: defaults.proxy,
        editorLspEnabled = stored["editor-lsp-enabled"] as? Boolean ?: defaults.editorLspEnabled,
    )
}

private fun validAdditionalPath(value: String): Boolean = value.isEmpty() || value.split(':').all {
    it.startsWith('/') && it.none { character -> character == '\u0000' || character == '\n' || character == '\r' }
}

private fun validDns(value: String): Boolean = value.split(',').all { address ->
    val parts = address.trim().split('.')
    parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }
}

/**
 * Persists user settings. The saved `proxy` value is always the credential-free endpoint;
 * SOCKS5 credentials live only in [ProxyCredentialsStore] (Android Keystore AES/GCM). Saving a
 * new endpoint without credentials clears the old pair so it can never be sent to a new server.
 */
class SettingsRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("development-settings", Context.MODE_PRIVATE)
    private val credentials = ProxyCredentialsStore(context)
    fun load() = settingsFromStorage(preferences.all)

    /** Upgrade cleanup: only the endpoint explicitly marked as our retired local bridge is removed.
     * Called on IO before startup publishes settings; manual loopback proxies are preserved.
     */
    internal fun migrateRemovedKernelProxy(): Boolean {
        val legacy = context.getSharedPreferences("proxy-kernel", Context.MODE_PRIVATE)
        val ownedPort = legacy.getInt("port", 0)
        if (ownedPort <= 0) return false
        val current = parseStored(preferences.getString("proxy", "").orEmpty())
        val owned = current?.isSocks == true && current.host == "127.0.0.1" && current.port == ownedPort
        if (owned) save(load().copy(proxy = ""))
        legacy.edit { remove("port") }
        return owned
    }

    fun saveEditorLspEnabled(enabled: Boolean) {
        preferences.edit { putBoolean("editor-lsp-enabled", enabled) }
    }

    fun validate(settings: AppSettings) {
        require(settings.terminalFontSp in 9..28) { context.getString(dev.zakocoder.app.R.string.invalid_font) }
        require(validAdditionalPath(settings.additionalPath)) { context.getString(dev.zakocoder.app.R.string.invalid_path) }
        require(validDns(settings.dns)) { context.getString(dev.zakocoder.app.R.string.invalid_dns) }
        if (settings.proxy.isNotBlank()) {
            // Blank means disabled; any other non-blank value must be an exact endpoint, with an
            // optional SOCKS5 username:password pair and no HTTP credentials.
            try {
                NetworkProxy.parse(settings.proxy)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(context.getString(dev.zakocoder.app.R.string.invalid_proxy))
            }
        }
    }
    // The editor switch is saved independently: a stale environment form must not overwrite it.
    fun save(settings: AppSettings) {
        validate(settings)
        val previousEndpoint = preferences.getString("proxy", "")!!.trim()
        val spec = if (settings.proxy.isBlank()) null else NetworkProxy.parse(settings.proxy)
        val endpoint = spec?.endpoint.orEmpty()
        val incoming = if (spec?.isSocks == true) NetworkProxy.credentials(settings.proxy) else null
        preferences.edit {
            putString("apt", settings.aptSource.name)
            putInt("font", settings.terminalFontSp)
            putString("shell", settings.terminalShell.name)
            putString("path", settings.additionalPath)
            putString("dns", settings.dns)
            putString("proxy", endpoint)
        }
        when {
            incoming != null -> credentials.save(endpoint, incoming)
            // Cleared, or moved to another endpoint: the previous pair no longer belongs here.
            spec == null || previousEndpoint != endpoint -> credentials.clear()
            // Same endpoint without an entered pair: keep the stored credentials.
        }
        // Keep the process-wide SOCKS5 authenticator in sync with the saved setting immediately:
        // switching endpoints replaces the endpoint-bound snapshot atomically, and disabling the
        // proxy clears it, so a stale pair can never be handed to a different proxy.
        NetworkProxy.configureSocksAuthentication(proxyForRuntime())
    }

    /** Credentials stored for the persisted SOCKS5 endpoint, if any. Safe to call off the UI. */
    fun loadProxyCredentials(): NetworkProxy.Credentials? {
        val endpoint = preferences.getString("proxy", "")!!.trim()
        if (endpoint.isEmpty()) return null
        val spec = parseStored(endpoint) ?: return null
        if (!spec.isSocks) return null
        return credentials.load(spec.endpoint)
    }

    /**
     * Value consumed by AI transport and the runtime download: the plain endpoint, or the full
     * URL with URL-encoded SOCKS5 credentials when one is stored. Internal only; never show it.
     */
    fun proxyForRuntime(): String {
        val endpoint = preferences.getString("proxy", "")!!.trim()
        if (endpoint.isEmpty()) return ""
        val spec = parseStored(endpoint) ?: return ""
        if (!spec.isSocks) return spec.endpoint
        val stored = credentials.load(spec.endpoint) ?: return spec.endpoint
        return spec.copy(username = stored.username, password = stored.password).proxyUrl
    }

    /** Masked value for the settings row; the password is never rendered. */
    fun proxyDisplay(): String {
        val endpoint = preferences.getString("proxy", "")!!.trim()
        if (endpoint.isEmpty()) return ""
        val spec = parseStored(endpoint) ?: return ""
        if (!spec.isSocks) return spec.endpoint
        val stored = credentials.load(spec.endpoint) ?: return spec.endpoint
        return spec.copy(username = stored.username, password = stored.password).display
    }

    /** A value persisted by an older/broken build must not crash the settings screen. */
    private fun parseStored(value: String): NetworkProxy.Spec? = try {
        NetworkProxy.parse(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
