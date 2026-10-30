package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Tests project presets at the actual controller tool authorization gate, using local TLS only. */
class AiPermissionPresetDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30000) { while (!condition()) delay(15) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.permissionsSaving &&
            !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "preset-files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "preset-private").apply { mkdirs() }
        }
        return AiController(context, { id -> File(f.root, "project-$id").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("No shell allowed in permission fixture") }, {}, {}, f.transport, f.config, history = f.history)
    }
    private suspend fun policy(ai: AiController, preset: AiPermissionPreset) {
        main { ai.setPolicy(AiPermissionPresets.apply(ai.state.value.policy, preset)) }; ready(ai)
    }
    private fun write(f: AiDeviceTest.Fixture, call: String, path: String, content: String) {
        f.enqueue(calls = listOf(AiCall(call, "write_file", JSONObject().put("path", path).put("content", content).put("reason", "Fixture authorization check").toString())))
        f.enqueue("Operation outcome acknowledged")
    }
    private fun drain(f: AiDeviceTest.Fixture) {
        repeat(2) { assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)) }
    }
    private suspend fun rejectedWrite(ai: AiController, f: AiDeviceTest.Fixture, call: String, path: String) {
        write(f, call, path, "must not be written")
        main { assertTrue(ai.send("Propose file $path")) }
        await { ai.state.value.approval != null || !ai.state.value.active }
        assertTrue(ai.state.value.active)
        assertNotNull(ai.state.value.approval)
        val waiting = ai.state.value.events.single { it.callId == call }
        assertEquals(AiTool.WriteFile, waiting.tool); assertEquals(AiToolStatus.Waiting, waiting.status)
        main { ai.approve(AiDecision.Reject) }; await { !ai.state.value.active }
        assertEquals(AiPhase.Completed, ai.state.value.phase)
        assertEquals(AiToolStatus.Denied, ai.state.value.events.single { it.callId == call }.status)
        drain(f)
    }
    private suspend fun noApprovalWrite(ai: AiController, f: AiDeviceTest.Fixture, call: String, path: String, expected: AiToolStatus) {
        write(f, call, path, "verified preset output")
        main { assertTrue(ai.send("Write fixture file $path")) }
        await { !ai.state.value.active || ai.state.value.approval != null }
        assertNull("Preset execution must not wait for approval", ai.state.value.approval)
        assertFalse(ai.state.value.active)
        assertEquals(AiPhase.Completed, ai.state.value.phase)
        assertEquals(expected, ai.state.value.events.single { it.callId == call }.status)
        drain(f)
    }

    @Test(timeout = 180000) fun normalAndElevatedPresetsChangeRealWriteGateAndPreserveProjectDenialsAfterRestart() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("normal") }; ready(ai)
            policy(ai, AiPermissionPreset.Normal)
            assertEquals(AiPermission.Ask, ai.state.value.policy.permission(AiTool.WriteFile))
            rejectedWrite(ai, f, "normal-write", "normal.txt")
            assertFalse(File(f.root, "project-normal/normal.txt").exists())

            main { ai.select("elevated") }; ready(ai)
            policy(ai, AiPermissionPreset.Elevated)
            assertEquals(AiPermission.Allow, ai.state.value.policy.permission(AiTool.WriteFile))
            noApprovalWrite(ai, f, "elevated-write", "allowed.txt", AiToolStatus.Succeeded)
            assertEquals("verified preset output", File(f.root, "project-elevated/allowed.txt").readText())

            main { ai.setPolicy(ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + (AiTool.WriteFile to AiPermission.Deny))) }; ready(ai)
            policy(ai, AiPermissionPreset.Elevated)
            assertEquals(AiPermission.Deny, ai.state.value.policy.permission(AiTool.WriteFile))
            noApprovalWrite(ai, f, "denied-write", "denied.txt", AiToolStatus.Denied)
            assertFalse(File(f.root, "project-elevated/denied.txt").exists())

            main { ai.select("normal") }; ready(ai)
            assertEquals(AiPermission.Ask, ai.state.value.policy.permission(AiTool.WriteFile))
            rejectedWrite(ai, f, "normal-after-switch", "normal-after-switch.txt")
            assertFalse(File(f.root, "project-normal/normal-after-switch.txt").exists())
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("elevated") }; ready(reopened)
            assertEquals(AiPermission.Deny, reopened.state.value.policy.permission(AiTool.WriteFile))
            noApprovalWrite(reopened, f, "denied-after-restart", "restart-denied.txt", AiToolStatus.Denied)
            assertFalse(File(f.root, "project-elevated/restart-denied.txt").exists())
            assertEquals("verified preset output", File(f.root, "project-elevated/allowed.txt").readText())
        }
    }
}
