package dev.zakocoder.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.lsp.LspServer
import dev.zakocoder.app.runtime.DevelopmentTools
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in network installation. Never run implicitly with ordinary editor regression tests. */
class LspProvisioningDeviceTest {
    @Test(timeout = 30 * 60 * 1000L) fun installRequestedLanguageServersUsingProductToolPackages() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("lspServers").orEmpty()
        val legacy = args.getString("installLanguageServers") == "true"
        assumeTrue("Provide lspServers=Java,Kotlin,Cpp,Go,Rust,Dart to opt in", requested.isNotBlank() || legacy)
        val servers = if (requested.isBlank()) listOf(LspServer.Python, LspServer.TypeScript) else requested.split(',').map { name ->
            LspServer.entries.singleOrNull { it.name.equals(name.trim(), ignoreCase = true) || it.bundleId == name.trim() }
                ?: throw AssertionError("Unknown explicitly requested LSP server: $name")
        }.distinct()
        val app = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZakoApplication).workbench
        withTimeout(30000) { while (app.state.value.loading || app.state.value.busy) delay(100) }
        assumeTrue("Ubuntu must already be installed", app.state.value.installed)
        check(!app.ai.state.value.active) { "Do not install while an AI task owns the runtime" }
        val settings = app.state.value.settings.let { current -> args.getString("lspProxy")?.let { current.copy(proxy = it) } ?: current }
        for (server in servers) {
            if (server == LspServer.Dart) {
                val dart = app.runtime.execute("test -x ${LspServer.Dart.executable}", null, settings)
                assumeTrue("Dart requires an existing Flutter SDK; this test never downloads the large SDK", dart.exitCode == 0)
            }
            val log: (String) -> Unit = { output -> Log.i("ZakoLspProvision", "${server.name}: ${output.takeLast(3000)}") }
            Log.i("ZakoLspProvision", "Installing explicitly requested ${server.name}")
            if (server.packages.isNotEmpty()) {
                val dependencies = app.runtime.execute(
                    DevelopmentTools.installScript(server.packages), null, settings,
                    timeoutMillis = 10 * 60 * 1000L, output = log,
                )
                assertEquals("${server.name} dependencies: ${dependencies.output.takeLast(4000)}", 0, dependencies.exitCode)
            }
            val installation = app.runtime.execute(server.installScript, null, settings,
                timeoutMillis = 20 * 60 * 1000L, output = log)
            assertEquals("${server.name} install: ${installation.output.takeLast(4000)}", 0, installation.exitCode)
            val detection = app.runtime.execute(server.detectScript, null, settings)
            assertEquals("${server.name} executable: ${detection.output}", 0, detection.exitCode)
            val probe = app.runtime.execute(server.probe, null, settings, output = log)
            assertEquals("${server.name} version probe: ${probe.output.takeLast(4000)}", 0, probe.exitCode)
            Log.i("ZakoLspProvision", "Installed and probed ${server.name}")
        }
    }
}
