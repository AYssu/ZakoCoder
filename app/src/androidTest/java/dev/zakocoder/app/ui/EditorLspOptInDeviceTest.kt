package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.editor.lsp.EditorLspController
import dev.zakocoder.app.editor.lsp.LspPhase
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.SettingsRepository
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Isolated project/preferences. Runtime availability always false: no detection or child process can run. */
@SdkSuppress(minSdkVersion = 30)
class EditorLspOptInDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    @Test fun oldPreferencesDefaultOffAndExplicitChoicePersists() {
        val suffix = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("lsp-opt-in-$suffix-$name", mode)
        }
        try {
            assertFalse(AppSettings().editorLspEnabled)
            assertFalse(SettingsRepository(isolated).load().editorLspEnabled)
            SettingsRepository(isolated).saveEditorLspEnabled(true)
            assertTrue(SettingsRepository(isolated).load().editorLspEnabled)
            // A delayed general-settings save must not overwrite this independent preference.
            SettingsRepository(isolated).save(AppSettings(terminalFontSp = 12, editorLspEnabled = false))
            assertTrue(SettingsRepository(isolated).load().editorLspEnabled)
            assertEquals(12, SettingsRepository(isolated).load().terminalFontSp)
            SettingsRepository(isolated).saveEditorLspEnabled(false)
            assertFalse(SettingsRepository(isolated).load().editorLspEnabled)
        } finally { context.deleteSharedPreferences("lsp-opt-in-$suffix-development-settings") }
    }

    @Test fun defaultNeverAttachesExplicitEnableAttachesAndDisableStopsAcrossFiles() {
        val root = File(context.cacheDir, "lsp-opt-in-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        }
        val projects = ProjectRepository(isolated, File(root, "projects"))
        val project = projects.create("LSP opt-in", mapOf("one.py" to "value = 1\n", "two.py" to "value = 2\n"))
        val availabilityChecks = AtomicInteger()
        val enabled = mutableStateOf(false)
        lateinit var controller: EditorController
        lateinit var lsp: EditorLspController
        var initialized = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    lsp = EditorLspController(isolated, LinuxRuntime(isolated), { AppSettings() }, {
                        availabilityChecks.incrementAndGet(); false
                    })
                    initialized = true
                    controller = EditorController(isolated, projects, {}, lsp = lsp)
                    activity.setContent {
                        CompositionLocalProvider(LocalWorkbenchDark provides false) {
                            MaterialTheme(colorScheme = WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                    CodeEditorPage(controller, lspEnabled = enabled.value, onLspEnabledChanged = { enabled.value = it })
                                }
                            }
                        }
                    }
                }
                // EditorController intentionally ignores open while its draft/language initialization is active.
                await { !controller.state.value.loading }
                instrumentation.runOnMainSync { controller.open(project.id, "one.py") }
                await { controller.state.value.path == "one.py" && controller.state.value.document != null && !controller.state.value.loading }
                settle()
                assertEquals("Default must not even check runtime availability", 0, availabilityChecks.get())
                assertEquals(LspPhase.Idle, lsp.state.value.phase)
                instrumentation.runOnMainSync { enabled.value = true }
                await { lsp.state.value.phase == LspPhase.RuntimeUnavailable }
                assertEquals(1, availabilityChecks.get())
                instrumentation.runOnMainSync { enabled.value = false }
                await { lsp.state.value.phase == LspPhase.Idle }
                instrumentation.runOnMainSync { controller.open(project.id, "two.py") }
                await { controller.state.value.path == "two.py" && !controller.state.value.loading }
                settle()
                assertEquals("Disabled remains off when switching files", 1, availabilityChecks.get())
                instrumentation.runOnMainSync { enabled.value = true }
                await { lsp.state.value.path == "two.py" && lsp.state.value.phase == LspPhase.RuntimeUnavailable }
                assertEquals(2, availabilityChecks.get())
                instrumentation.runOnMainSync { enabled.value = false }
                await { lsp.state.value.phase == LspPhase.Idle }
            }
        } finally {
            if (initialized) instrumentation.runOnMainSync { lsp.detach() }
            root.deleteRecursively()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!predicate()) { check(SystemClock.uptimeMillis() < deadline) { "LSP opt-in condition timed out" }; Thread.sleep(40) }
        instrumentation.waitForIdleSync()
    }
    private fun settle() { instrumentation.waitForIdleSync(); Thread.sleep(400); instrumentation.waitForIdleSync() }
}
