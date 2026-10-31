package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.lsp.LspServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in download, separate from the offline regression suite. */
class LspProvisioningDeviceTest {
    @Test(timeout = 20 * 60 * 1000L) fun installRequestedLanguageServersUsingProductToolPackages() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Language-server downloads must be explicitly requested", args.getString("installLanguageServers") == "true")
        val app = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZakoApplication).workbench
        withTimeout(30000) { while (app.state.value.loading || app.state.value.busy) delay(100) }
        check(app.state.value.installed && !app.ai.state.value.active)
        val ids = setOf("lsp-python", "lsp-typescript")
        withContext(Dispatchers.Main) { app.installToolBundles(ids) }
        withTimeout(19 * 60 * 1000L) { while (app.state.value.busy) delay(250) }
        assertFalse(app.state.value.message, app.state.value.messageIsError)
        for (server in LspServer.entries) {
            val result = app.runtime.execute(server.detectScript, null, app.state.value.settings)
            assertEquals(server.name + ": " + result.output, 0, result.exitCode)
        }
    }
}
