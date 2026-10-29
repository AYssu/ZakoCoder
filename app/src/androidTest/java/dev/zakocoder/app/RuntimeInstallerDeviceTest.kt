package dev.zakocoder.app

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.*
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses an isolated private files/cache subtree; never replaces the user's installed environment. */
class RuntimeInstallerDeviceTest {
    @Test fun rejectCorruptOfflineImageCancelAndRetryFreshInstall() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(base.cacheDir, "runtime-download/rootfs.tar.xz")
        assertTrue("Keep the verified download cache until this installer test has run", image.isFile)
        val temporary = File(base.filesDir, "installer-check-${UUID.randomUUID()}")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(temporary, "files")
            override fun getCacheDir() = File(temporary, "cache")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                withContext(Dispatchers.IO) { context.filesDir.mkdirs(); context.cacheDir.mkdirs() }
                val runtime = LinuxRuntime(context)
                withContext(Dispatchers.IO) { runtime.workspaces.mkdirs() }
                val corrupt = File(temporary, "corrupt.tar.xz")
                withContext(Dispatchers.IO) { corrupt.writeText("corrupt test image") }
                var rejected = false
                try { runtime.install(AppSettings(), Uri.fromFile(corrupt)) {} }
                catch (error: IllegalStateException) { rejected = true }
                assertTrue("Corrupt input must fail verification", rejected)
                assertFalse(withContext(Dispatchers.IO) { runtime.root.exists() })
                val extracting = CompletableDeferred<Unit>()
                val installing = launch(Dispatchers.IO) {
                    runtime.install(AppSettings(), Uri.fromFile(image)) { status ->
                        if (status == context.getString(R.string.runtime_extracting, 200)) extracting.complete(Unit)
                    }
                }
                withTimeout(30000) { extracting.await() }
                withContext(Dispatchers.IO) { installing.cancelAndJoin() }
                assertFalse(withContext(Dispatchers.IO) { runtime.isInstalled() })
                assertFalse(withContext(Dispatchers.IO) { File(runtime.home, "installing").exists() })
                withTimeout(3 * 60 * 1000L) { runtime.install(AppSettings()) {} }
                assertTrue(withContext(Dispatchers.IO) { runtime.isInstalled() })
                val result = runtime.execute("set -e; test \"${'$'}(/bin/pwd)\" = /root; printf 'FRESH_INSTALL_OK\\n'", null, AppSettings())
                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("FRESH_INSTALL_OK"))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(temporary) }
            }
        }
    }
}
