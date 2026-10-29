package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.NpmCompatibility
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in shared tool repair; only its generated project/cache are removed. System npm remains intact. */
class NpmCompatibilityDeviceTest {
    @Test(timeout = 240000) fun installCompatibleNpmAndBuildViteWithFreshCache() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairDeviceNpm") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (w.state.value.loading) delay(50) }
            assertTrue(w.state.value.installed)
            assertFalse(w.state.value.busy || w.ai.state.value.active || w.projectTasks.state.value.busy)
            val installed = w.runtime.execute(NpmCompatibility.installScript(), null, w.state.value.settings, timeoutMillis = 240000)
            assertEquals(installed.output, 0, installed.exitCode)
            assertTrue(installed.output, installed.output.contains(NpmCompatibility.version))
            val project = withContext(Dispatchers.IO) { w.projects.create("npm-compatibility-check") }
            try {
                withContext(Dispatchers.IO) {
                    val directory = w.projects.directory(project.id)
                    File(directory, "package.json").writeText("""{"private":true,"scripts":{"build":"vite build"},"devDependencies":{"vite":"5.4.21"}}""")
                    File(directory, "index.html").writeText("<!doctype html><title>npm compatibility fixture</title><script type=module src=/main.js></script>")
                    File(directory, "main.js").writeText("document.body.textContent = 'built with real Vite';")
                }
                val result = w.runtime.execute("""
                    set -eu
                    test "${'$'}(npm --version)" = '${NpmCompatibility.version}'
                    /usr/bin/npm --version
                    npm --userconfig=./user.npmrc --globalconfig=./global.npmrc install --cache ./.test-cache --registry=https://registry.npmjs.org --no-audit --no-fund --fetch-retries=0 --fetch-timeout=30000
                    npm run build
                    test -s dist/index.html
                    npm --userconfig=./user.npmrc --globalconfig=./global.npmrc ci --cache ./.test-cache --offline --no-audit --no-fund
                    npm run build
                    printf 'ZAKO_NPM_INSTALL_CI_BUILD_OK\n' 
                """.trimIndent(), project.id, w.state.value.settings, timeoutMillis = 180000)
                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("ZAKO_NPM_INSTALL_CI_BUILD_OK"))
            } finally {
                withContext(Dispatchers.IO) { PrivateFiles.deleteTree(w.projects.directory(project.id)) }
            }
        }
    }
}
