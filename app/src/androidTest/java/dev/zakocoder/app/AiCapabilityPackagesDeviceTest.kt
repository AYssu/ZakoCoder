package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.screentest.ScreenTestResult
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Actual HTTPS request bodies and approval races; no remote host or screen is operated. */
class AiCapabilityPackagesDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(10) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.permissionsLoading &&
            !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private fun controller(f: AiDeviceTest.Fixture, packages: () -> Map<CapabilityPackage, String>,
        executed: (CapabilityPackage) -> Unit = {}): AiController {
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "controller-files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "controller-private").apply { mkdirs() }
        }
        return AiController(context, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("No command expected") }, {}, {}, f.transport, f.config, history = f.history,
            capabilitySnapshot = { packages() },
            screenTestTool = { _, _, _ -> executed(CapabilityPackage.Screen); ScreenTestResult("screen status") },
            screenTestLease = { "selected-target" },
            sshTool = { _, _, _ -> executed(CapabilityPackage.Ssh); JSONObject().put("connected", false) })
    }
    private fun call(kind: CapabilityPackage) = AiCall("optional-${kind.wire}",
        if (kind == CapabilityPackage.Screen) "android_test" else "ssh_remote",
        JSONObject().put("action", "status").put("reason", "Check selected capability").toString())
    private fun request(f: AiDeviceTest.Fixture): JSONObject =
        JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
    private fun names(body: JSONObject): Set<String> = body.getJSONArray("tools").let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    }

    @Test(timeout = 120000) fun defaultRequestsOmitOptionalSchemasAndDedicatedInstructions() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f, { emptyMap() }); ai.select("packages-default") }; ready(ai)
            f.enqueue("Source-only answer")
            main { assertTrue(ai.send("Explain this project")) }; await { !ai.state.value.active }
            val body = request(f)
            assertFalse(names(body).contains("android_test")); assertFalse(names(body).contains("ssh_remote"))
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertFalse(system.contains("android_test")); assertFalse(system.contains("ssh_remote"))
        }
    }

    @Test(timeout = 180000) fun eachEnabledPackageAddsOnlyItsOwnSchemaAndInstructions() = runBlocking {
        for (kind in CapabilityPackage.entries) AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f, { mapOf(kind to "enabled-session") }); ai.select("packages-${kind.wire}") }; ready(ai)
            f.enqueue("Explanation without operating devices")
            main { assertTrue(ai.send("Describe available tools")) }; await { !ai.state.value.active }
            val body = request(f)
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertEquals(kind == CapabilityPackage.Screen, "android_test" in names(body))
            assertEquals(kind == CapabilityPackage.Ssh, "ssh_remote" in names(body))
            assertEquals(kind == CapabilityPackage.Screen, system.contains("android_test"))
            assertEquals(kind == CapabilityPackage.Ssh, system.contains("ssh_remote"))
        }
    }

    @Test(timeout = 180000) fun unsolicitedDisabledCallsCannotReachEitherExecutor() = runBlocking {
        for (kind in CapabilityPackage.entries) AiDeviceTest.Fixture(base).use { f ->
            var executed = 0
            lateinit var ai: AiController
            main { ai = controller(f, { emptyMap() }) { executed++ }; ai.select("disabled-${kind.wire}") }; ready(ai)
            f.enqueue(calls = listOf(call(kind))); f.enqueue("Capability is unavailable")
            main { assertTrue(ai.send("Inspect capability")) }; await { !ai.state.value.active }
            assertEquals(0, executed); assertNull(ai.state.value.approval)
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
            assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_capability_disabled)))
        }
    }

    @Test(timeout = 180000) fun approvalCannotReviveDisabledThenReenabledPackageLease() = runBlocking {
        for (kind in CapabilityPackage.entries) AiDeviceTest.Fixture(base).use { f ->
            var packages = mapOf(kind to "before-approval")
            var executed = 0
            lateinit var ai: AiController
            main { ai = controller(f, { packages }) { executed++ }; ai.select("lease-${kind.wire}") }; ready(ai)
            main { ai.setPolicy(AiPolicy(AiPermission.Allow, emptyMap())) }; await { !ai.state.value.permissionsSaving }
            f.enqueue(calls = listOf(call(kind))); f.enqueue("Old approval was rejected")
            main { assertTrue(ai.send("Read capability status")) }; await { ai.state.value.approval != null }
            assertEquals(0, executed)
            main { packages = emptyMap(); packages = mapOf(kind to "after-reenable"); ai.approve(AiDecision.TaskTool) }
            await { !ai.state.value.active }
            assertEquals(0, executed)
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
            assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_capability_disabled)))
            request(f)
            val after = request(f)
            assertFalse(names(after).contains(if (kind == CapabilityPackage.Screen) "android_test" else "ssh_remote"))
        }
    }
}
