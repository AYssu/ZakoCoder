package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Full-only installation checks use disposable data; they never alter the user's Ubuntu/projects. */
class BundledRuntimeDeviceTest {
    @Test fun bundledImageInstallsOfflineAndNeverOverwritesExistingEnvironment() = runBlocking {
        assumeTrue(BuildConfig.BUNDLED_ROOTFS)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(base.filesDir, "bundled-runtime-check-${UUID.randomUUID()}")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(temporary, "files")
            override fun getCacheDir() = File(temporary, "cache")
        }
        try {
            withContext(Dispatchers.IO) { context.filesDir.mkdirs(); context.cacheDir.mkdirs() }
            val runtime = LinuxRuntime(context)
            val settings = AppSettings(proxy = "http://127.0.0.1:1") // Any network fallback would fail.
            withContext(Dispatchers.IO) {
                runtime.root.mkdirs()
                File(runtime.root, "user-file").writeText("preserve")
            }
            try { runtime.install(settings) {}; fail("Existing environment was overwritten") }
            catch (_: IllegalStateException) { }
            assertEquals("preserve", withContext(Dispatchers.IO) { File(runtime.root, "user-file").readText() })
            withContext(Dispatchers.IO) { PrivateFiles.deleteTree(runtime.root) }
            val extracting = CompletableDeferred<Unit>()
            val installation = launch {
                runtime.install(settings) { status ->
                    if (status == context.getString(R.string.runtime_extracting, 200)) extracting.complete(Unit)
                }
            }
            withTimeout(90_000) { extracting.await() }
            installation.cancelAndJoin()
            assertFalse(withContext(Dispatchers.IO) { runtime.isInstalled() })
            assertFalse(withContext(Dispatchers.IO) { File(runtime.home, "installing").exists() })
            // Remove the verified cache too: the retry must source the APK image again, offline.
            withContext(Dispatchers.IO) { PrivateFiles.deleteTree(File(context.cacheDir, "runtime-download")) }
            withTimeout(180_000) { runtime.install(settings) {} }
            assertTrue(withContext(Dispatchers.IO) { runtime.isInstalled() })
            val result = runtime.execute("printf 'BUNDLED_RUNTIME_OK\\n'", null, settings)
            assertEquals(result.output, 0, result.exitCode)
            assertTrue(result.output.contains("BUNDLED_RUNTIME_OK"))
        } finally { withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(temporary) } }
    }
}
