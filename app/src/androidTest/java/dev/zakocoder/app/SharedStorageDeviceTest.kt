package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.AndroidSharedStorage
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.runtime.SharedStorageMounts
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses the actual OS grant and dedicated UUID fixtures; never modifies a user's runtime or project. */
class SharedStorageDeviceTest {
    @Test fun currentAuthorizationControlsAliasesBeforeEnvWithoutUbuntu(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = File(base.cacheDir, "shared-storage-args-${UUID.randomUUID()}")
            val context = isolatedContext(base, fixture)
            try {
                val runtime = LinuxRuntime(context)
                assertTrue(runtime.root.mkdirs())
                val mounts = AndroidSharedStorage.current(context)
                InstrumentationRegistry.getArguments().getString("storageGranted")?.let { expected ->
                    require(expected == "true" || expected == "false") { "storageGranted must be true or false" }
                    assertEquals("OS storage grant/readability did not match the requested test state", expected.toBoolean(), mounts.source != null)
                }
                val command = runtime.command(null, AppSettings(), "true")
                assertMountArguments(command.arguments, mounts)
                assertEquals("/root", command.arguments[command.arguments.indexOf("-w") + 1])
                assertEquals(mounts.modelContext(), runtime.sharedStorageContext())
                assertFalse("Argument construction must not install Ubuntu", File(runtime.root, "usr/bin/bash").exists())
            } finally {
                PrivateFiles.deleteTree(fixture)
            }
        }
    }

    /** Opt in with sharedStorageProbe=true after staging a verified image and granting file access. */
    @Test(timeout = 240_000) fun isolatedOfflineUbuntuReadsAndWritesTheRealSharedFixture(): Unit = runBlocking {
        assumeTrue("Opt-in offline PRoot probe", InstrumentationRegistry.getArguments().getString("sharedStorageProbe") == "true")
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val image = File(base.cacheDir, "native-runtime-verification/rootfs.tar.xz")
            assertTrue("Stage the matching verified rootfs image first", image.isFile)
            val mounts = AndroidSharedStorage.current(base)
            assertNotNull("Grant file access before running the opt-in shared storage probe", mounts.source)
            val source = requireNotNull(mounts.source)
            val token = UUID.randomUUID().toString()
            val installation = File(base.cacheDir, "shared-storage-runtime-$token")
            val context = isolatedContext(base, installation)
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/ZakoCoder-shared-storage-$token"
            val sharedFixture = File(source, relative)
            try {
                assertTrue("Create an exclusively owned shared fixture", sharedFixture.mkdirs())
                context.filesDir.mkdirs()
                context.cacheDir.mkdirs()
                val runtime = LinuxRuntime(context)
                runtime.workspaces.mkdirs()
                runtime.install(AppSettings(), Uri.fromFile(image)) {}
                assertTrue(runtime.isInstalled())

                val original = "HOST_INITIAL_$token"
                val updated = "GUEST_UPDATED_$token"
                val probeFile = File(sharedFixture, "probe file.txt")
                probeFile.writeText(original)
                val guestPaths = mounts.paths.map { "$it/$relative/${probeFile.name}" }
                assertEquals("Current primary path plus two stable aliases", 3, guestPaths.size)
                val script = buildString {
                    appendLine("set -eu")
                    appendLine("test \"${'$'}(pwd -P)\" = /root")
                    for (path in guestPaths) appendLine("test \"${'$'}(cat ${quote(path)})\" = ${quote(original)}")
                    appendLine("printf '%s' ${quote(updated)} > ${quote(guestPaths[1])}")
                    for (path in guestPaths) appendLine("test \"${'$'}(cat ${quote(path)})\" = ${quote(updated)}")
                    appendLine("printf '%s\\n' ${quote("SHARED_STORAGE_OK_$token")}")
                }
                val command = runtime.command(null, AppSettings(), script)
                assertMountArguments(command.arguments, mounts)
                val result = runtime.execute(script, null, AppSettings(), timeoutMillis = 30_000)
                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("SHARED_STORAGE_OK_$token"))
                assertEquals("Guest edits must reach the original host file", updated, probeFile.readText())
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { PrivateFiles.deleteTree(sharedFixture) }
                    finally { PrivateFiles.deleteTree(installation) }
                }
            }
        }
    }

    private fun isolatedContext(base: Context, fixture: File): Context = object : ContextWrapper(base) {
        override fun getFilesDir() = File(fixture, "files")
        override fun getCacheDir() = File(fixture, "cache")
    }

    private fun assertMountArguments(arguments: List<String>, mounts: SharedStorageMounts) {
        val env = arguments.indexOf("/usr/bin/env")
        assertTrue("Guest env must exist after PRoot options", env >= 0)
        val bindings = arguments.windowed(2).filter { it[0] == "-b" }.map { it[1] }
        if (mounts.source == null) {
            assertFalse(bindings.toString(), bindings.any { it.endsWith(":/sdcard") || it.endsWith(":/storage/self/primary") })
        } else {
            for (path in mounts.paths) {
                val binding = "${mounts.source.path}:$path"
                assertTrue(bindings.toString(), binding in bindings)
                val index = arguments.indexOf(binding)
                assertTrue("Storage bindings must precede guest env: $binding", index in 1 until env)
                assertEquals("-b", arguments[index - 1])
            }
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
