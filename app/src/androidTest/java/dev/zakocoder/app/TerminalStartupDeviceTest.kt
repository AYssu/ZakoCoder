package dev.zakocoder.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.DevelopmentTools
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.TerminalShell
import dev.zakocoder.app.terminal.TerminalItem
import dev.zakocoder.app.terminal.TerminalManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Opt-in device measurement. Never opens a user project, closes user sessions or changes settings.
 * A "first" sample starts a fresh PTY, not an artificially purged shell/OS cache. View timings cover
 * view creation and attachment on Main, not a displayed frame or keyboard animation.
 */
class TerminalStartupDeviceTest {
    @Test(timeout = 600000) fun measurePreparationPromptAndReopenWithAndWithoutDoctor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LinuxRuntime(context)
        val workbench = (context.applicationContext as ZakoApplication).workbench
        val rows = JSONArray()
        val report = JSONObject().put("samples", rows)
        val id = UUID.randomUUID().toString()
        val directory = File(runtime.workspaces, id)
        val manager = TerminalManager(context) { }
        try {
            assertTrue("Preinstalled Ubuntu required", withContext(Dispatchers.IO) { runtime.isInstalled() })
            // Let the real Application startup doctor finish without cancelling any existing work.
            val appStartup = SystemClock.elapsedRealtimeNanos()
            withTimeout(90000) {
                while (workbench.state.value.loading || workbench.state.value.checkingTools) delay(50)
            }
            report.put("application_initial_check_wait_ms", elapsedMs(appStartup))
            withContext(Dispatchers.IO) {
                check(directory.mkdirs())
                manager.prepareTypeface()
            }
            for (shell in TerminalShell.entries) {
                val available = withContext(Dispatchers.IO) {
                    File(runtime.root, shell.executable.removePrefix("/")).isFile
                }
                if (!available) {
                    rows.put(JSONObject().put("shell", shell.name).put("skipped", "not_installed"))
                    continue
                }
                val settings = AppSettings(terminalShell = shell)
                repeat(2) { index ->
                    rows.put(sample(runtime, manager, id, settings, "idle_${if (index == 0) "first" else "warm"}"))
                }
                // Reproduce the same probe work used by Workbench, without reading saved proxy/model
                // settings. No versions or command output are retained, only elapsed time and status.
                val started = CompletableDeferred<Unit>()
                val doctorStart = SystemClock.elapsedRealtimeNanos()
                var doctorTail = ""
                var doctorFirstOutputMs = 0.0
                val doctor = async(Dispatchers.IO) {
                    runtime.execute("printf 'ZAKO_STARTUP_DOCTOR_READY\\n'\n" + DevelopmentTools.doctorScript(DevelopmentTools.catalog),
                        null, settings, timeoutMillis = 60000, output = { chunk ->
                            doctorTail = (doctorTail + chunk).takeLast(256)
                            if (doctorTail.contains("ZAKO_STARTUP_DOCTOR_READY") && !started.isCompleted) {
                                doctorFirstOutputMs = elapsedMs(doctorStart)
                                started.complete(Unit)
                            }
                        })
                }
                try {
                    withTimeout(15000) {
                        while (!started.isCompleted && !doctor.isCompleted) delay(20)
                    }
                    assertTrue("Doctor must start before contention sample", started.isCompleted)
                    val row = sample(runtime, manager, id, settings, "parallel_doctor")
                    row.put("doctor_still_running_when_prompt_ready", !doctor.isCompleted)
                    row.put("doctor_first_output_ms", doctorFirstOutputMs)
                    rows.put(row)
                    try {
                        val result = doctor.await()
                        row.put("doctor_exit", result.exitCode)
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        row.put("doctor_timed_out", true)
                    }
                    row.put("doctor_ms", elapsedMs(doctorStart))
                } finally {
                    doctor.cancel()
                    doctor.join()
                }
            }
        } finally {
            // The independent manager only owns this test's PTYs; Workbench sessions are untouched.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { manager.closeAll() }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                if (directory.exists()) PrivateFiles.deleteTree(directory)
                File(context.cacheDir, "terminal-startup-validation.json").writeText(report.toString(2))
            }
        }
        println("TERMINAL_STARTUP_METRICS " + report.toString())
    }

    private suspend fun sample(
        runtime: LinuxRuntime,
        manager: TerminalManager,
        projectId: String,
        settings: AppSettings,
        condition: String,
    ): JSONObject {
        val row = JSONObject().put("shell", settings.terminalShell.name).put("condition", condition)
        row.put("zcompdump_before", cacheMetadata(runtime))
        var item: TerminalItem? = null
        try {
            val prepareStart = SystemClock.elapsedRealtimeNanos()
            withContext(Dispatchers.IO) { runtime.prepareTerminal(settings) }
            row.put("prepare_terminal_ms", elapsedMs(prepareStart))
            val commandStart = SystemClock.elapsedRealtimeNanos()
            val command = runtime.command(projectId, settings, null, projectName = "Startup fixture")
            row.put("runtime_command_ms", elapsedMs(commandStart))
            val promptStart = SystemClock.elapsedRealtimeNanos()
            val created = withContext(Dispatchers.Main) { manager.create(projectId, "Startup fixture", command) }
            item = created
            row.put("pty_create_main_ms", elapsedMs(promptStart))
            val session = created.session
            val markerSuffix = UUID.randomUUID().toString().replace("-", "")
            val marker = "ZAKO_PROMPT_$markerSuffix"
            // The echoed input contains separated string arguments, not the complete marker, so
            // readiness requires the shell to finish its startup and actually execute printf.
            withContext(Dispatchers.Main) {
                session.write("printf '%s%s\\n' 'ZAKO_PROMPT_' '$markerSuffix'\n")
            }
            withTimeout(45000) {
                while (!withContext(Dispatchers.Main) {
                    val emulator = session.emulator
                    assertTrue("Fixture PTY ended before becoming ready", session.isRunning)
                    emulator != null && emulator.screen.getSelectedText(0,
                        -emulator.screen.activeTranscriptRows, emulator.mColumns, emulator.mRows - 1).contains(marker)
                }) delay(20)
            }
            row.put("pty_ready_roundtrip_ms", elapsedMs(promptStart))
            val reopen = JSONArray()
            val originalPid = session.pid
            repeat(5) {
                withContext(Dispatchers.Main) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val view = manager.view(InstrumentationRegistry.getInstrumentation().targetContext, created, 11)
                    val width = view.resources.displayMetrics.widthPixels
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, 800)
                    reopen.put(elapsedMs(started))
                    assertEquals(originalPid, view.currentSession.pid)
                    manager.detach(view)
                }
            }
            row.put("existing_session_view_attach_ms", reopen)
            row.put("same_pty_after_reopen", session.pid == originalPid)
            row.put("zcompdump_after", cacheMetadata(runtime))
            return row
        } finally {
            item?.let { owned -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { manager.close(owned.id) } }
        }
    }

    private suspend fun cacheMetadata(runtime: LinuxRuntime): JSONObject = withContext(Dispatchers.IO) {
        val path = File(runtime.root, "root/.cache/zakocoder/zcompdump").toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) JSONObject().put("exists", false)
        else {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            JSONObject().put("exists", true).put("symlink", attributes.isSymbolicLink)
                .put("size", attributes.size()).put("modified_ms", attributes.lastModifiedTime().toMillis())
        }
    }

    private fun elapsedMs(startNanos: Long): Double = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1000000.0
}
