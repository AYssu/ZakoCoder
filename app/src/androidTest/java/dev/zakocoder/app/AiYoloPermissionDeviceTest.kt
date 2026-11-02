package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.screentest.ScreenTestApk
import dev.zakocoder.app.screentest.ScreenTestApkProposal
import dev.zakocoder.app.screentest.ScreenTestResult
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real controller gates and local TLS; fake executors never operate another app or remote host. */
class AiYoloPermissionDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val apk = "app/build/outputs/apk/debug/app-debug.apk"
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, condition: () -> Boolean) = withTimeout(45000) {
        while (!condition()) delay(10)
        assertTrue(label, condition())
    }
    private suspend fun ready(ai: AiController) = await("controller ready") {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.permissionsSaving &&
            !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private suspend fun preset(ai: AiController, preset: AiPermissionPreset) {
        main { ai.setPolicy(AiPermissionPresets.apply(ai.state.value.policy, preset)) }; ready(ai)
    }
    private fun call(tool: String, action: String = "status") = AiCall("$tool-$action", tool,
        JSONObject().put("reason", "Verify explicitly selected project policy").apply {
            when (tool) {
                "use_mcp" -> put("server_id", "fixture").put("method", "tools/list").put("params", JSONObject())
                "write_file" -> put("path", "second.txt").put("content", "must be approved")
                else -> { put("action", action); if (action == "prepare") put("path", apk) }
            }
        }.toString())
    private fun context(f: AiDeviceTest.Fixture) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(f.root, "yolo-files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "yolo-private").apply { mkdirs() }
    }
    private fun controller(f: AiDeviceTest.Fixture, packages: () -> Map<CapabilityPackage, String> = {
        mapOf(CapabilityPackage.Screen to "screen-generation", CapabilityPackage.Ssh to "ssh-generation")
    }, mcp: suspend () -> Unit = {}, screen: () -> Unit = {}, ssh: () -> Unit = {},
        lease: () -> String? = { "selected-debug-target" }, preview: suspend () -> Unit = {}, select: () -> Unit = {}) =
        AiController(context(f), { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("This test never runs shell commands") }, {}, {}, f.transport, f.config, history = f.history,
            capabilitySnapshot = { packages() },
            mcpCatalog = { "Enabled fixture MCP server" }, mcpPreview = { "Fixture MCP operation" },
            mcpTool = { _, _ -> mcp(); "{}" },
            screenTestTool = { _, _, _ -> screen(); ScreenTestResult("Screen fixture observed") },
            screenTestLease = { lease() },
            screenTestPreview = { _, path ->
                assertEquals(apk, path); preview()
                ScreenTestApkProposal(ScreenTestApk(path, "dev.zakocoder.fixture", "Fixture", "1", true), "a".repeat(64))
            },
            screenTestSelect = { _, path, digest -> assertEquals(apk, path); assertEquals("a".repeat(64), digest); select() },
            sshTool = { _, _, _ -> ssh(); JSONObject().put("fixture", true) })
    private suspend fun done(ai: AiController) {
        await("task finished without pending tool approval") { !ai.state.value.active || ai.state.value.approval != null }
        assertNull("YOLO must not leave tool approval pending", ai.state.value.approval)
        assertFalse(ai.state.value.active)
        assertEquals(AiPhase.Completed, ai.state.value.phase)
    }

    @Test(timeout = 120000) fun yoloAutomaticallyAllowsMcpScreenAndSshAtRealAuthorizationGate() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var mcp = 0; var screen = 0; var ssh = 0
            lateinit var ai: AiController
            main { ai = controller(f, mcp = { mcp++ }, screen = { screen++ }, ssh = { ssh++ }); ai.select("yolo-tools") }
            ready(ai); preset(ai, AiPermissionPreset.Yolo)
            f.enqueue(calls = listOf(call("use_mcp"), call("android_test", "observe"), call("ssh_remote")))
            f.enqueue("All three configured fixture tools executed")
            main { assertTrue(ai.send("Exercise configured tools")) }; done(ai)
            assertEquals(1, mcp); assertEquals(1, screen); assertEquals(1, ssh)
            assertEquals(3, ai.state.value.events.size)
            ai.state.value.events.forEach { event ->
                assertEquals(AiToolStatus.Succeeded, event.status)
                assertEquals(AiPermission.Allow, event.permission)
                assertEquals(R.string.ai_permission_yolo_rule, event.permissionSource)
            }
            val restored = withContext(Dispatchers.IO) { requireNotNull(f.history.load("yolo-tools").state) }
            assertEquals(3, restored.events.size)
            restored.events.forEach { assertEquals(R.string.ai_permission_yolo_rule, it.permissionSource) }
        }
    }

    @Test(timeout = 120000) fun yoloCanPrepareCurrentProjectDebugArtifactWithoutAnotherToolApproval() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var selectedLease: String? = null
            var selections = 0; var operations = 0
            lateinit var ai: AiController
            main {
                ai = controller(f, lease = { selectedLease }, select = { selections++; selectedLease = "prepared-debug" }, screen = { operations++ })
                ai.select("yolo-prepare")
            }; ready(ai); preset(ai, AiPermissionPreset.Yolo)
            f.enqueue(calls = listOf(call("android_test", "prepare"), call("android_test", "launch")))
            f.enqueue("Project artifact prepared, launch requested; not a UI assertion")
            main { assertTrue(ai.send("Prepare selected project's APK")) }; done(ai)
            assertEquals(1, selections); assertEquals(1, operations)
            assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Succeeded })
        }
    }

    @Test(timeout = 120000) fun enablingYoloWakesPendingApprovalAndDisablingItRequiresNextToolApproval() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            lateinit var ai: AiController
            main { ai = controller(f, mcp = { entered.complete(Unit); release.await() }); ai.select("yolo-live-change") }
            ready(ai)
            f.enqueue(calls = listOf(call("use_mcp"), call("write_file")))
            f.enqueue("MCP completed, later write was rejected")
            try {
                main { assertTrue(ai.send("Run fixture MCP then write a file")) }
                await("ordinary MCP asks") { ai.state.value.approval != null }
                assertEquals(AiTool.UseMcp, ai.state.value.events.first().tool)
                preset(ai, AiPermissionPreset.Yolo)
                withTimeout(45000) { entered.await() }
                assertNull(ai.state.value.approval)
                preset(ai, AiPermissionPreset.Normal)
                release.complete(Unit)
                await("next tool asks after revocation") { ai.state.value.approval != null }
                assertEquals(AiTool.WriteFile, ai.state.value.events.last().tool)
                assertEquals(AiToolStatus.Waiting, ai.state.value.events.last().status)
                main { ai.approve(AiDecision.Reject) }; await("task finished") { !ai.state.value.active }
                assertFalse(File(f.root, "project-yolo-live-change/second.txt").exists())
                assertEquals(AiToolStatus.Succeeded, ai.state.value.events.first().status)
                assertEquals(AiToolStatus.Denied, ai.state.value.events.last().status)
            } finally { release.complete(Unit); main { ai.stop() }; await("cleanup") { !ai.state.value.active } }
        }
    }

    @Test(timeout = 120000) fun yoloDoesNotEnableDisabledCapabilityPackages() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var operations = 0
            lateinit var ai: AiController
            main { ai = controller(f, packages = { emptyMap() }, screen = { operations++ }, ssh = { operations++ }); ai.select("yolo-disabled-packages") }
            ready(ai); preset(ai, AiPermissionPreset.Yolo)
            f.enqueue(calls = listOf(call("android_test"), call("ssh_remote")))
            f.enqueue("Disabled packages were not operated")
            main { assertTrue(ai.send("Check unavailable capabilities")) }; done(ai)
            assertEquals(0, operations)
            assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Failed })
        }
    }

    @Test(timeout = 120000) fun yoloCannotFollowAReplacementScreenTargetAfterPreview() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var lease: String? = null; var selections = 0
            lateinit var ai: AiController
            main { ai = controller(f, lease = { lease }, preview = { lease = "user-replacement" }, select = { selections++ }); ai.select("yolo-target-race") }
            ready(ai); preset(ai, AiPermissionPreset.Yolo)
            f.enqueue(calls = listOf(call("android_test", "prepare")))
            f.enqueue("User replacement was preserved")
            main { assertTrue(ai.send("Prepare current artifact")) }; done(ai)
            assertEquals(0, selections); assertEquals("user-replacement", lease)
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
        }
    }

    @Test fun yoloPersistsPerProjectAndLegacyBroadAllowDoesNotBecomeYolo() {
        AiDeviceTest.Fixture(base).use { f ->
            val isolated = context(f)
            val permissions = File(isolated.filesDir, "yolo-persistence.json")
            permissions.writeText(JSONObject()
                .put("legacy", JSONObject().put("default", "Allow").put("tools", JSONObject()))
                .put("invalid-string", JSONObject().put("default", "Allow").put("tools", JSONObject()).put("yolo", "true"))
                .toString())
            val store = AiPermissionStore(isolated, "yolo-persistence")
            assertFalse(store.load("legacy").yolo)
            assertFalse("Only a native persisted boolean enables YOLO", store.load("invalid-string").yolo)
            assertEquals(AiPolicy(), store.load("other"))
            val yolo = AiPermissionPresets.apply(AiPolicy(), AiPermissionPreset.Yolo)
            store.save("chosen", yolo)
            val reopened = AiPermissionStore(isolated, "yolo-persistence")
            assertEquals(yolo, reopened.load("chosen"))
            assertFalse(reopened.load("legacy").yolo)
            assertEquals(AiPolicy(), reopened.load("other"))
            reopened.save("chosen", AiPermissionPresets.apply(yolo, AiPermissionPreset.Normal))
            assertFalse(AiPermissionStore(isolated, "yolo-persistence").load("chosen").yolo)
        }
    }
}
