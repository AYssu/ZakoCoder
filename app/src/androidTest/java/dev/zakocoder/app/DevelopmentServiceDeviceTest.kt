package dev.zakocoder.app

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.screentest.ScreenTestAccess
import dev.zakocoder.app.terminal.DevelopmentService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in target package only. Requires a signed Debug fixture at /data/local/tmp/fixture.apk.
 * No root, target launch, system setting edits, production data or provider configuration. */
class DevelopmentServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) = withTimeout(20000) {
        while (!predicate()) delay(50)
        check(predicate()) { label }
    }
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(output.size() + count <= 8 * 1024 * 1024) { "Bounded system diagnostic exceeded" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
    private suspend fun serviceDump() = shell("dumpsys activity services ${context.packageName}")
    private fun foreground(dump: String) = dump.contains("dev.zakocoder.app.terminal.DevelopmentService") &&
        Regex("isForeground\\s*=\\s*true").containsMatchIn(dump)
    private suspend fun taskLockHeld(): Boolean = shell("dumpsys power").lineSequence().any {
        it.contains("PARTIAL_WAKE_LOCK") && it.contains("${context.packageName}:development-task")
    }
    private suspend fun runningPids() = shell("pidof ${context.packageName}").trim().split(Regex("\\s+"))
        .mapNotNull(String::toIntOrNull).toSet()
    private suspend fun copyFixture(destination: File) = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/fixture.apk")).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192); var countTotal = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    countTotal += count; check(countTotal <= 32L * 1024 * 1024) { "Fixture APK exceeds 32 MiB" }
                    output.write(buffer, 0, count)
                }
                check(countTotal > 0) { "Provide /data/local/tmp/fixture.apk before this test" }
            }
        }
    }

    @Test(timeout = 120000) fun selectedNormalScreenTargetKeepsForegroundServiceAcrossHomeAndReleasesCpuLock() = runBlocking {
        assumeTrue("This test must never operate on the production app", context.packageName == "dev.zakocoder.app.runtimecheck")
        warmDeviceActivity()
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await("Workbench initial load") { !workbench.state.value.loading }
        check(!workbench.state.value.busy && !workbench.state.value.backgroundServiceRunning &&
            !workbench.ai.state.value.active && !workbench.git.state.value.busy && !workbench.mcp.state.value.running &&
            !workbench.projectTasks.state.value.busy && !workbench.screenTests.state.value.active &&
            !workbench.screenTests.state.value.busy && workbench.terminal.sessions.none { it.session.isRunning }) {
            "Run this isolated fixture only after existing test tasks and terminals have stopped"
        }
        val screen = workbench.screenTests
        val originalAccess = screen.state.value.access
        val originalOverlay = screen.state.value.overlayEnabled
        val project = withContext(Dispatchers.IO) { workbench.projects.create("Service check ${UUID.randomUUID().toString().take(8)}") }
        val projectRoot = withContext(Dispatchers.IO) { workbench.projects.directory(project.id) }
        var service: DevelopmentService? = null
        var report: File? = null
        try {
            copyFixture(File(projectRoot, "fixture.apk"))
            main { screen.setOverlayEnabled(false); screen.setAccess(ScreenTestAccess.Normal) }
            screen.selectApk(project.id, "fixture.apk")
            report = screen.state.value.reportPath?.let(::File)
            await("Selected screen target starts foreground service") { workbench.state.value.backgroundServiceRunning }
            assertFalse(workbench.state.value.backgroundServiceError)
            assertTrue(screen.state.value.active)
            assertEquals(ScreenTestAccess.Normal, screen.state.value.access)
            assertFalse(screen.state.value.overlayEnabled)
            assertFalse(screen.state.value.overlayVisible)
            assertTrue("System must report the actual foreground service", foreground(serviceDump()))
            assertFalse("An idle selected target must not hold the execution wake lock", taskLockHeld())
            main {
                val field = Workbench::class.java.getDeclaredField("foregroundService").apply { isAccessible = true }
                service = field.get(workbench) as? DevelopmentService
                requireNotNull(service).setTaskActive(true)
            }
            assertTrue("Active work holds the named partial wake lock", taskLockHeld())
            main { requireNotNull(service).setTaskActive(false) }
            assertFalse("Finishing work releases the CPU lock even while selection stays active", taskLockHeld())

            val pid = android.os.Process.myPid()
            assertTrue(runningPids().contains(pid))
            shell("input keyevent KEYCODE_HOME")
            withTimeout(10000) {
                while (true) {
                    val focus = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus=") }
                    if (focus != null && !focus.contains(context.packageName) && !focus.contains("null")) break
                    delay(100)
                }
            }
            delay(30000)
            assertEquals("HOME does not restart this process", setOf(pid), runningPids())
            assertTrue(screen.state.value.active)
            assertEquals(project.id, screen.state.value.projectId)
            assertTrue(workbench.state.value.backgroundServiceRunning)
            assertTrue("Foreground promotion remains real after HOME", foreground(serviceDump()))
            assertFalse("Idle background selection still does not retain a CPU lock", taskLockHeld())
            main { screen.stop() }
            await("Stopping selection releases its only service owner") { !workbench.state.value.backgroundServiceRunning }
            withTimeout(10000) { while (foreground(serviceDump())) delay(50) }
            assertFalse(screen.state.value.active)
            assertFalse(taskLockHeld())
        } finally {
            withContext(NonCancellable) {
                main {
                    service?.setTaskActive(false)
                    if (screen.state.value.projectId == project.id) screen.stop()
                    screen.setAccess(originalAccess)
                    screen.setOverlayEnabled(originalOverlay)
                }
                await("Service cleanup") { !workbench.state.value.backgroundServiceRunning }
                if (report != null) screen.readReport(project.id) // Drain only this fixture's final audit write before cleanup.
                withContext(Dispatchers.IO) {
                    report?.let { file ->
                        check(file.canonicalFile.parentFile == File(context.filesDir, "screen-test/reports").canonicalFile)
                        check(file.name.endsWith(".jsonl"))
                        file.delete()
                    }
                    // Repository.create returned this fresh UUID directory; registry and all other projects stay untouched.
                    check(projectRoot.name == project.id)
                    projectRoot.deleteRecursively()
                }
                warmDeviceActivity()
            }
        }
    }
}
