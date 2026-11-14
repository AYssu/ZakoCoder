package dev.zakocoder.app.ui

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiPermission
import dev.zakocoder.app.ai.AiPermissionPreset
import dev.zakocoder.app.ai.AiPermissionPresets
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.ai.AiTool
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Four visible approval modes using local fixture storage, without real model or project data. */
@SdkSuppress(minSdkVersion = 30)
class AiPermissionModesUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui = UpgradeUiHarness()

    @Before fun foreground() {
        check(context.packageName.endsWith(".runtimecheck"))
        warmDeviceActivity()
    }

    @Test(timeout = 180000) fun dayFourModesConfirmationsAndComposer() = runBlocking { review(false) }
    @Test(timeout = 180000) fun nightFourModesConfirmationsAndComposer() = runBlocking { review(true) }

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(ready: () -> Boolean) = withTimeout(45000) { while (!ready()) delay(25) }

    private suspend fun review(dark: Boolean) {
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val key = "${fixture.token}-$name"
                synchronized(preferences) { preferences.add(key) }
                return context.getSharedPreferences(key, mode)
            }
            override fun stopService(service: android.content.Intent) = false
        }
        val store = AiPermissionStore(isolated)
        var owned: Workbench? = null
        var sheet by mutableStateOf(true)
        val theme = if (dark) "night" else "day"
        try {
            main { owned = Workbench(isolated, fixture.transport, fixture.config, store, fixture.history) }
            val workbench = requireNotNull(owned)
            await { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            main { workbench.create("权限模式验证") }
            await { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            main { workbench.ai.select(project.id) }
            await {
                val state = workbench.ai.state.value
                state.projectId == project.id && state.configured && !state.restoring &&
                    !state.permissionsLoading && !state.permissionsSaving && !state.skillsLoading
            }
            assertEquals(Glyph.ReadOnly, permissionModeGlyph(AiPermissionPreset.ReadOnly))
            assertEquals(Glyph.ShieldAsk, permissionModeGlyph(AiPermissionPreset.Normal))
            assertEquals(Glyph.ShieldAuto, permissionModeGlyph(AiPermissionPreset.Elevated))
            assertEquals(Glyph.Unlocked, permissionModeGlyph(AiPermissionPreset.Yolo))
            assertEquals(4, AiPermissionPreset.entries.map(::permissionModeGlyph).toSet().size)

            suspend fun persisted(preset: AiPermissionPreset) {
                await { !workbench.ai.state.value.permissionsSaving && AiPermissionPresets.matching(workbench.ai.state.value.policy) == preset }
                assertEquals(workbench.ai.state.value.policy, withContext(Dispatchers.IO) { store.load(project.id) })
            }
            suspend fun composer(preset: AiPermissionPreset) {
                ui.await("Selection automatically closes the permission sheet") { !sheet }
                ui.await("Mode is announced by the icon without a visible label") {
                    ui.has(context.getString(permissionModeLabel(preset)) + " · " + context.getString(
                        R.string.ai_permission_current, context.getString(permissionModeLabel(preset)), workbench.ai.state.value.policy.overrides.size)) &&
                        !ui.has(permissionModeLabel(preset))
                }
                ui.capture("permission-modes/$theme-composer-${preset.name.lowercase()}")
                main { sheet = true }
                ui.await("Permission modes reopen") { ui.has(R.string.ai_permission_preset_normal_desc) }
            }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ui.configureSystemBars(activity, dark)
                    activity.setContent {
                        val state by workbench.state.collectAsStateWithLifecycle()
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                                AiPage(workbench, state, onModels = {}, onProjects = {}, onTerminal = {})
                                if (sheet) AiPermissionsSheet(workbench, project.name) { sheet = false }
                            }
                        }
                    }
                }
                ui.await("Four mode titles visible") { AiPermissionPreset.entries.all { ui.has(permissionModeLabel(it)) } }
                ui.capture("permission-modes/$theme-four-modes")

                ui.click(R.string.ai_permission_preset_readonly)
                persisted(AiPermissionPreset.ReadOnly)
                assertFalse("Read-only mode needs no dangerous grant dialog", ui.has(R.string.ai_auto_allow_confirm))
                assertEquals(AiPermission.Ask, workbench.ai.state.value.policy.permission(AiTool.WriteFile))
                composer(AiPermissionPreset.ReadOnly)

                ui.click(R.string.ai_permission_preset_normal)
                ui.await("Normal grants require confirmation") { ui.has(R.string.ai_auto_allow_title) }
                assertEquals(AiPermissionPreset.ReadOnly, AiPermissionPresets.matching(workbench.ai.state.value.policy))
                ui.capture("permission-modes/$theme-normal-confirm")
                ui.click(R.string.cancel)
                persisted(AiPermissionPreset.ReadOnly)
                ui.click(R.string.ai_permission_preset_normal)
                ui.click(R.string.ai_auto_allow_confirm)
                persisted(AiPermissionPreset.Normal)
                composer(AiPermissionPreset.Normal)

                ui.click(R.string.ai_permission_preset_elevated)
                ui.await("Smart approval requires confirmation") { ui.has(R.string.ai_auto_allow_title) }
                ui.click(R.string.ai_auto_allow_confirm)
                persisted(AiPermissionPreset.Elevated)
                composer(AiPermissionPreset.Elevated)

                ui.click(R.string.ai_permission_preset_yolo)
                ui.await("Full Access danger warning appears before change") { ui.has(R.string.ai_permission_yolo_confirm_title) }
                assertFalse(workbench.ai.state.value.policy.yolo)
                ui.capture("permission-modes/$theme-full-access-warning")
                ui.click(R.string.cancel)
                persisted(AiPermissionPreset.Elevated)
                ui.click(R.string.ai_permission_preset_yolo)
                ui.click(R.string.ai_auto_allow_confirm)
                persisted(AiPermissionPreset.Yolo)
                assertTrue(workbench.ai.state.value.policy.yolo)
                composer(AiPermissionPreset.Yolo)
                ui.capture("permission-modes/$theme-full-access-selected")
                assertEquals("Choosing approval modes must not call the model", 0, fixture.server.requestCount)
            }
        } finally {
            main { owned?.stopAll() }
            await { owned?.ai?.state?.value?.active != true }
            withContext(Dispatchers.IO) {
                synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
                fixture.close()
            }
        }
    }
}
