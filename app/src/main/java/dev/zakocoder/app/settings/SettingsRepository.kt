package dev.zakocoder.app.settings

import android.content.Context
import androidx.core.content.edit

enum class AptSource(val baseUrl: String) {
    Ubuntu("https://ports.ubuntu.com/ubuntu-ports"),
    Tsinghua("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"),
}

enum class TerminalShell(val executable: String) { Bash("/bin/bash"), Zsh("/usr/bin/zsh") }

data class AppSettings(
    val aptSource: AptSource = AptSource.Ubuntu,
    val terminalFontSp: Int = 13,
    val terminalShell: TerminalShell = TerminalShell.Bash,
    val additionalPath: String = "",
    val dns: String = "1.1.1.1,8.8.8.8",
    val proxy: String = "",
)

class SettingsRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("development-settings", Context.MODE_PRIVATE)
    fun load() = AppSettings(
        aptSource = AptSource.valueOf(preferences.getString("apt", AptSource.Ubuntu.name)!!),
        terminalFontSp = preferences.getInt("font", AppSettings().terminalFontSp),
        terminalShell = TerminalShell.entries.firstOrNull { it.name == preferences.getString("shell", "Bash") } ?: TerminalShell.Bash,
        additionalPath = preferences.getString("path", "")!!,
        dns = preferences.getString("dns", "1.1.1.1,8.8.8.8")!!,
        proxy = preferences.getString("proxy", "")!!,
    )

    fun validate(settings: AppSettings) {
        require(settings.terminalFontSp in 10..28) { context.getString(dev.zakocoder.app.R.string.invalid_font) }
        require(settings.additionalPath.isEmpty() || settings.additionalPath.split(':').all {
            it.startsWith('/') && it.none { character -> character == '\u0000' || character == '\n' || character == '\r' }
        }) { context.getString(dev.zakocoder.app.R.string.invalid_path) }
        require(settings.dns.split(',').all { address ->
            val parts = address.trim().split('.')
            parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }
        }) { context.getString(dev.zakocoder.app.R.string.invalid_dns) }
        if (settings.proxy.isNotBlank()) {
            // Blank means disabled; any other non-blank value must be an exact HTTP/SOCKS5 endpoint.
            try {
                NetworkProxy.parse(settings.proxy)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(context.getString(dev.zakocoder.app.R.string.invalid_proxy))
            }
        }
    }
    fun save(settings: AppSettings) {
        validate(settings)
        preferences.edit {
            putString("apt", settings.aptSource.name)
            putInt("font", settings.terminalFontSp)
            putString("shell", settings.terminalShell.name)
            putString("path", settings.additionalPath)
            putString("dns", settings.dns)
            putString("proxy", settings.proxy.trim())
        }
    }
}
