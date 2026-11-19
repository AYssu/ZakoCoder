package dev.zakocoder.app.device

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Android processes and isolated state; never reads user SSH/API credentials or changes Root manager policy. */
class DeviceAccessDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    private class Fixture(private val base: Context) : AutoCloseable {
        private val token = "device-shell-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { check(mkdirs()) }
        private val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = root
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token-$name".also { names += it }, mode)
        }
        fun rootSelected() {
            check(context.getSharedPreferences("device_access", Context.MODE_PRIVATE).edit().putString("mode", "root").commit())
        }
        override fun close() {
            names.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    @Test(timeout = 30_000) fun basicUsesApplicationUidAndPreservesNonZeroExitStatus() = runBlocking {
        Fixture(base).use { fixture ->
            val access = DeviceAccessController(fixture.context)
            access.state.first { it.loaded }
            try {
                assertEquals(DeviceMode.Basic, access.state.value.mode)
                assertFalse(access.ensureRoot())
                val result = access.execute("id -u; printf device_stderr >&2; exit 7")
                assertEquals(Process.myUid(), result.uid)
                assertEquals("android_app", result.identity)
                assertEquals(Process.myUid().toString(), result.stdout.trim())
                assertEquals("device_stderr", result.stderr)
                assertEquals(7, result.exitCode)
                assertFalse(result.timedOut)
            } finally { access.stop() }
        }
    }

    @Test(timeout = 30_000) fun longSingleLineIsChunkedAndResultIsBounded() = runBlocking {
        Fixture(base).use { fixture ->
            val access = DeviceAccessController(fixture.context)
            access.state.first { it.loaded }
            val chunks = java.util.concurrent.atomic.AtomicInteger()
            val longest = java.util.concurrent.atomic.AtomicInteger()
            try {
                val result = access.execute("head -c 300000 /dev/zero | tr '\\000' x", onOutput = {
                    chunks.incrementAndGet(); longest.accumulateAndGet(it.length, ::maxOf)
                })
                assertEquals(0, result.exitCode)
                assertTrue(result.truncated)
                assertEquals(256 * 1024, result.stdout.length)
                assertTrue(chunks.get() > 1)
                assertTrue(longest.get() in 1..4096)
            } finally { access.stop() }
        }
    }

    @Test(timeout = 60_000) fun selectedRootUsesUidZeroAndBasicRevokesItsGeneration() = runBlocking {
        Fixture(base).use { fixture ->
            // Restored mode is not a restored grant. No automatic optional permission changes in this fixture.
            fixture.rootSelected()
            val access = DeviceAccessController(fixture.context)
            access.state.first { it.loaded }
            try {
                assertFalse(access.state.value.rootGranted)
                assumeTrue("Device Root authorization required for this device-only case", access.ensureRoot())
                val result = access.execute("id -u; exit 13")
                assertEquals(0, result.uid)
                assertEquals("0", result.stdout.trim())
                assertEquals(13, result.exitCode)
                assertEquals("android_root", result.identity)
                val generation = access.generation
                access.useBasic()
                assertTrue(access.generation > generation)
                assertEquals(DeviceMode.Basic, access.state.value.mode)
                assertFalse(access.state.value.rootGranted)
                assertFalse(access.ensureRoot())
                assertEquals(Process.myUid(), access.execute("id -u").uid)
                val restored = DeviceAccessController(fixture.context)
                restored.state.first { it.loaded }
                assertEquals(DeviceMode.Basic, restored.state.value.mode)
                restored.stop()
            } finally { access.stop() }
        }
    }

    @Test(timeout = 60_000) fun timeoutStopsOwnedChildProcessesWithoutWaitingForSleep() = runBlocking {
        Fixture(base).use { fixture ->
            fixture.rootSelected()
            val access = DeviceAccessController(fixture.context)
            access.state.first { it.loaded }
            try {
                assumeTrue("Device Root authorization required for child ownership validation", access.ensureRoot())
                val child = File(fixture.root, "sleep-child.pid").apply { writeText("") }
                val result = access.execute("sleep 90 & printf '%s' \"\$!\" > '${child.absolutePath}'; wait", timeoutSeconds = 1)
                assertTrue(result.timedOut)
                val pid = child.readText().trim().toInt()
                withTimeout(8_000) {
                    while (access.execute("kill -0 $pid 2>/dev/null").exitCode == 0) delay(100)
                }
            } finally { access.stop() }
        }
    }

    @Test(timeout = 30_000) fun cancellationStopsBasicChildProcesses() = runBlocking {
        Fixture(base).use { fixture ->
            val access = DeviceAccessController(fixture.context)
            access.state.first { it.loaded }
            try {
                val child = File(fixture.root, "cancel-child.pid").apply { writeText("") }
                val task = async { access.execute("sleep 90 & printf '%s' \"\$!\" > '${child.absolutePath}'; wait") }
                withTimeout(5_000) { while (child.readText().isBlank()) delay(20) }
                val pid = child.readText().trim().toInt()
                task.cancelAndJoin()
                withTimeout(8_000) {
                    while (access.execute("kill -0 $pid 2>/dev/null").exitCode == 0) delay(100)
                }
            } finally { access.stop() }
        }
    }

    @Test(timeout = 60_000) fun missingOrDeniedRootNeverFallsBackToApplicationShell() = runBlocking {
        Fixture(base).use { fixture ->
            val runner = DeviceShell(fixture.context)
            val root = runCatching { runner.execute(true, "id -u", 20, {}) }
            assumeTrue("Run on an unrooted emulator or with Root denied; rooted device skips denial case", root.isFailure)
            fixture.rootSelected()
            val access = DeviceAccessController(fixture.context)
            access.state.first { it.loaded }
            try {
                assertFalse(access.ensureRoot())
                assertEquals(DeviceMode.Root, access.state.value.mode)
                assertTrue(runCatching { access.execute("id -u") }.isFailure)
                assertFalse(access.state.value.rootGranted)
            } finally { access.stop(); runner.stop() }
        }
    }
}
