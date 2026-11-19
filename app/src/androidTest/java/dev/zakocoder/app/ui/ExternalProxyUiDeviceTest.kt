package dev.zakocoder.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.settings.NetworkProxy
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test

/** External endpoints only: production form, validation, save/disable and both theme variants. */
@SdkSuppress(minSdkVersion = 30)
class ExternalProxyUiDeviceTest {
    @Test(timeout = 90_000)
    fun manualHttpSocksAndDisableInBothThemes() {
        warmDeviceActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = UpgradeUiHarness()
        var dark by mutableStateOf(false)
        var endpoint by mutableStateOf("")
        var display by mutableStateOf("")
        val saved = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(dark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ProxySettingsContent(endpoint, display, false, {}, onSave = { value ->
                            val spec = NetworkProxy.parse(value)
                            saved += spec?.endpoint.orEmpty()
                            endpoint = spec?.endpoint.orEmpty()
                            display = spec?.display.orEmpty()
                        })
                    }
                }
            } }
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync { dark = night; endpoint = ""; display = "" }
                ui.await("Proxy form ready") { ui.has(R.string.proxy_section_manual) }
                ui.capture("external-proxy/${if (night) "night" else "day"}-direct")
                ui.setField(R.string.proxy_manual_label, "https://proxy.example:443")
                ui.click(R.string.proxy_manual_save)
                ui.await("Invalid proxy address is explained") { ui.has(R.string.proxy_manual_invalid) }
                assertEquals("", endpoint)
                ui.setField(R.string.proxy_manual_label, "http://127.0.0.1:7890")
                ui.click(R.string.proxy_manual_save)
                ui.await("HTTP proxy applied") { endpoint == "http://127.0.0.1:7890" }
                ui.scrollTop()
                ui.capture("external-proxy/${if (night) "night" else "day"}-http")
                ui.setField(R.string.proxy_manual_label, "socks5://127.0.0.1:1080")
                ui.click(R.string.proxy_manual_save)
                ui.await("SOCKS5 proxy applied") { endpoint == "socks5://127.0.0.1:1080" }
                ui.click(R.string.proxy_direct_save)
                ui.await("Proxy disabled") { endpoint.isEmpty() }
            }
            assertEquals(listOf("http://127.0.0.1:7890", "socks5://127.0.0.1:1080", "",
                "http://127.0.0.1:7890", "socks5://127.0.0.1:1080", ""), saved)
        }
    }
}
