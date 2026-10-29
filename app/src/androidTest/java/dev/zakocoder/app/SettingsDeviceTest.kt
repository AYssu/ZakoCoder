package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.settings.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SettingsDeviceTest {
    @Test fun settingsRoundTripAndRejectInvalidInput(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val workbench = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
            val original = workbench.state.value.settings
            val changed = original.copy(aptSource = AptSource.Tsinghua, terminalFontSp = 18, additionalPath = "/opt/bin", dns = "9.9.9.9")
            try {
                instrumentation.runOnMainSync { workbench.saveSettings(changed) }
                awaitIdle(workbench)
                assertEquals(changed, workbench.state.value.settings)
                assertEquals(changed, withContext(Dispatchers.IO) { SettingsRepository(context).load() })
                assertEquals("nameserver 9.9.9.9\n", withContext(Dispatchers.IO) { File(workbench.runtime.root, "etc/resolv.conf").readText() })
                instrumentation.runOnMainSync { workbench.saveSettings(changed.copy(dns = "999.1.1.1")) }
                awaitIdle(workbench)
                assertEquals(changed, workbench.state.value.settings)
                assertEquals(changed, withContext(Dispatchers.IO) { SettingsRepository(context).load() })
                withContext(Dispatchers.IO) {
                    val repository = SettingsRepository(context)
                    assertThrows(IllegalArgumentException::class.java) { repository.validate(changed.copy(additionalPath = "relative/path")) }
                    assertThrows(IllegalArgumentException::class.java) { repository.validate(changed.copy(proxy = "https://example.invalid:443")) }
                }
            } finally {
                instrumentation.runOnMainSync { workbench.saveSettings(original) }
                awaitIdle(workbench)
            }
        }
    }
    private suspend fun awaitIdle(workbench: Workbench) { withTimeout(10000) { while (workbench.state.value.busy) delay(50) } }
}
