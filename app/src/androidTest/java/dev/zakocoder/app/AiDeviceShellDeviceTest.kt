package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.device.DeviceAccessController
import dev.zakocoder.app.device.DeviceMode
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real Android app-UID commands through the AI loop, with isolated state and a local TLS model. */
class AiDeviceShellDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45_000) {
        while (!condition()) delay(10)
    }
    private suspend fun ready(ai: AiController) = await {
        val state = ai.state.value
        state.configured && !state.permissionsLoading && !state.permissionsSaving &&
            !state.skillsLoading && !state.restoring && !ai.models.state.value.loading
    }

    private class Fixture(base: Context, rootSelected: Boolean) : AutoCloseable {
        private val token = "ai-device-shell-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, token).apply { check(mkdirs()) }
        private val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
        private val baseContext = base
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token-$name".also { preferenceNames.add(it) }, mode)
        }
        init {
            if (rootSelected) check(context.getSharedPreferences("device_access", Context.MODE_PRIVATE)
                .edit().putString("mode", "root").commit())
        }
        val model = AiDeviceTest.Fixture(context)
        val access = DeviceAccessController(context)
        val enabled = AtomicBoolean(true)
        val capabilityGeneration = AtomicLong()
        val executions = AtomicInteger()
        val chunks = AtomicInteger()
        fun snapshot(): Map<CapabilityPackage, String> = if (!enabled.get() || !access.state.value.loaded) emptyMap()
            else mapOf(CapabilityPackage.DeviceShell to
                "fixture:${capabilityGeneration.get()}:${access.generation}:${access.state.value.mode.name}")
        fun controller() = AiController(context,
            { File(model.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("The device-shell fixture must never execute a PRoot command") }, {}, {},
            model.transport, model.config, history = model.history,
            capabilitySnapshot = { snapshot() },
            deviceShellDescription = {
                if (access.state.value.mode == DeviceMode.Root) "Android Root UID 0" else "Android application UID"
            },
            deviceShellTool = { _, args, lease, output ->
                check(lease != null && snapshot()[CapabilityPackage.DeviceShell] == lease)
                executions.incrementAndGet()
                val result = access.execute(args.getString("command"), args.optInt("timeout_seconds", 10)) {
                    chunks.incrementAndGet()
                    output(it)
                }
                check(snapshot()[CapabilityPackage.DeviceShell] == lease)
                JSONObject().put("environment", "android_host").put("cwd", "/")
                    .put("uid", result.uid).put("identity", result.identity)
                    .put("stdout", result.stdout).put("stderr", result.stderr)
                    .put("exit_code", result.exitCode).put("timed_out", result.timedOut)
                    .put("truncated", result.truncated).toString()
            })
        override fun close() {
            access.stop()
            model.close()
            preferenceNames.toList().forEach(baseContext::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private suspend fun fixture(rootSelected: Boolean = false, block: suspend (Fixture, AiController) -> Unit) {
        Fixture(base, rootSelected).use { f ->
            f.access.state.first { it.loaded }
            lateinit var ai: AiController
            main { ai = f.controller(); ai.select("device-shell-fixture") }
            try { ready(ai); block(f, ai) }
            finally {
                main { ai.stop() }
                f.access.stop()
                await { !ai.state.value.active }
            }
        }
    }
    private fun call(id: String, command: String) = AiCall(id, "device_shell", JSONObject()
        .put("command", command).put("timeout_seconds", 10).put("reason", "Verify the isolated Android shell fixture").toString())
    private fun request(f: Fixture): JSONObject = JSONObject(requireNotNull(
        f.model.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
    private fun names(body: JSONObject): Set<String> = body.getJSONArray("tools").let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    }
    private fun result(body: JSONObject, id: String): JSONObject = body.getJSONArray("messages").let { messages ->
        (0 until messages.length()).map(messages::getJSONObject).single {
            it.optString("role") == "tool" && it.optString("tool_call_id") == id
        }.let { JSONObject(it.getString("content")) }
    }
    private suspend fun preset(ai: AiController, preset: AiPermissionPreset) {
        main { ai.setPolicy(AiPermissionPresets.apply(ai.state.value.policy, preset)) }
        ready(ai)
    }

    @Test(timeout = 120_000) fun defaultRequestsAndUnsolicitedCallsCannotEnableDeviceShell() = runBlocking<Unit> {
        fixture { f, ai ->
            f.enabled.set(false)
            f.model.enqueue("No device capability is enabled")
            main { assertTrue(ai.send("Explain the available tools")) }
            await { !ai.state.value.active }
            val first = request(f)
            assertFalse("device_shell" in names(first))
            assertFalse(first.getJSONArray("messages").getJSONObject(0).getString("content").contains("device_shell"))
            preset(ai, AiPermissionPreset.Yolo)
            f.model.enqueue(calls = listOf(call("disabled-host", "printf must-not-execute")))
            f.model.enqueue("The disabled capability was rejected")
            main { assertTrue(ai.send("Inspect the unavailable host shell")) }
            await { !ai.state.value.active }
            assertEquals(0, f.executions.get())
            assertNull(ai.state.value.approval)
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
            assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_capability_disabled)))
            assertFalse("device_shell" in names(request(f)))
            assertFalse("device_shell" in names(request(f)))
        }
    }

    @Test(timeout = 120_000) fun basicShellStreamsBeforeCompletionAndReturnsActualUidAndExitCodeToModel() = runBlocking<Unit> {
        fixture { f, ai ->
            val release = File(f.model.root, "release-command")
            val quoted = "'" + release.absolutePath.replace("'", "'\\''") + "'"
            preset(ai, AiPermissionPreset.Yolo)
            f.model.enqueue(calls = listOf(call("stream-host", "/system/bin/id -u; printf 'HOST_STREAM_START\\n'; " +
                "while [ ! -f $quoted ]; do sleep 0.05; done; printf HOST_STREAM_END; printf HOST_STDERR >&2; exit 7")))
            f.model.enqueue("The real Android command returned exit code 7")
            try {
                main { assertTrue(ai.send("Run the host-shell streaming fixture")) }
                await { ai.state.value.events.any { it.callId == "stream-host" && it.output.contains("HOST_STREAM_START") } }
                val running = ai.state.value.events.single { it.callId == "stream-host" }
                assertTrue(ai.state.value.active)
                assertEquals(AiToolStatus.Running, running.status)
                assertFalse(running.output.contains("HOST_STREAM_END"))
                assertTrue(f.chunks.get() > 0)
                withContext(Dispatchers.IO) { release.writeText("continue") }
                await { !ai.state.value.active }
                assertEquals(1, f.executions.get())
                assertEquals("", ai.state.value.error)
                val event = ai.state.value.events.single()
                assertEquals(AiToolStatus.Failed, event.status)
                assertEquals(7, event.exitCode)
                val first = request(f)
                assertTrue("device_shell" in names(first))
                assertFalse("ssh_remote" in names(first))
                assertFalse("android_test" in names(first))
                assertFalse("browser" in names(first))
                val actual = result(request(f), "stream-host")
                assertEquals(Process.myUid(), actual.getInt("uid"))
                assertEquals("android_app", actual.getString("identity"))
                assertEquals("android_host", actual.getString("environment"))
                assertEquals("/", actual.getString("cwd"))
                assertEquals("${Process.myUid()}\nHOST_STREAM_START\nHOST_STREAM_END", actual.getString("stdout"))
                assertEquals("HOST_STDERR", actual.getString("stderr"))
                assertEquals(7, actual.getInt("exit_code"))
                assertFalse(actual.getBoolean("timed_out"))
            } finally { withContext(Dispatchers.IO) { release.writeText("continue") } }
        }
    }

    @Test(timeout = 120_000) fun reenabledCapabilityCannotReuseAnEarlierApproval() = runBlocking<Unit> {
        fixture { f, ai ->
            main { ai.setPolicy(AiPolicy(AiPermission.Ask, emptyMap())) }; ready(ai)
            f.model.enqueue(calls = listOf(call("old-package", "printf must-not-execute")))
            f.model.enqueue("The old capability lease was rejected")
            main { assertTrue(ai.send("Run a host command after approval")) }
            await { ai.state.value.approval != null }
            assertEquals(0, f.executions.get())
            f.enabled.set(false)
            f.capabilityGeneration.incrementAndGet()
            f.enabled.set(true)
            main { ai.approve(AiDecision.TaskTool) }
            await { !ai.state.value.active }
            assertEquals(0, f.executions.get())
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
            assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_capability_disabled)))
            assertTrue("device_shell" in names(request(f)))
            assertFalse("device_shell" in names(request(f)))
        }
    }

    @Test(timeout = 120_000) fun switchingRootToBasicInvalidatesPendingApprovalWithoutRequestingRoot() = runBlocking<Unit> {
        fixture(rootSelected = true) { f, ai ->
            // Isolated persisted selection only: this case never opens su or grants system permissions.
            assertEquals(DeviceMode.Root, f.access.state.value.mode)
            assertFalse(f.access.state.value.rootGranted)
            main { ai.setPolicy(AiPolicy(AiPermission.Ask, emptyMap())) }; ready(ai)
            f.model.enqueue(calls = listOf(call("old-root", "printf must-not-execute")))
            f.model.enqueue("The previous Root approval has expired")
            main { assertTrue(ai.send("Run a command with the selected device identity")) }
            await { ai.state.value.approval != null }
            val lease = f.snapshot()[CapabilityPackage.DeviceShell]
            val generation = f.access.generation
            f.access.useBasic()
            assertTrue(f.access.generation > generation)
            assertNotEquals(lease, f.snapshot()[CapabilityPackage.DeviceShell])
            main { ai.approve(AiDecision.TaskTool) }
            await { !ai.state.value.active }
            assertEquals(0, f.executions.get())
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
            assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_capability_disabled)))
            request(f); request(f)

            f.model.enqueue(calls = listOf(call("new-basic", "/system/bin/id -u")))
            f.model.enqueue("A newly approved Basic command uses the application identity")
            main { assertTrue(ai.send("Now run a Basic host identity check")) }
            await { ai.state.value.approval != null }
            main { ai.approve(AiDecision.Once) }
            await { !ai.state.value.active }
            assertEquals(1, f.executions.get())
            request(f)
            assertEquals(Process.myUid(), result(request(f), "new-basic").getInt("uid"))
            assertEquals(DeviceMode.Basic, f.access.state.value.mode)
            assertFalse(f.access.state.value.rootGranted)
        }
    }

    @Test(timeout = 120_000) fun onlyApprovalModeRequiresConsentForHostShell() = runBlocking<Unit> {
        fixture { f, ai ->
            preset(ai, AiPermissionPreset.ReadOnly)
            f.model.enqueue(calls = listOf(call("only-host", "printf must-not-execute")))
            f.model.enqueue("The user declined the host command")
            main { assertTrue(ai.send("Propose an Android host command")) }
            await { ai.state.value.approval != null }
            assertEquals(0, f.executions.get())
            assertEquals(AiTool.DeviceShell, ai.state.value.events.single().tool)
            main { ai.approve(AiDecision.Reject) }
            await { !ai.state.value.active }
            assertEquals(0, f.executions.get())
            assertEquals(AiToolStatus.Denied, ai.state.value.events.single().status)
        }
    }

    @Test(timeout = 120_000) fun readonlyPlanningOmitsAndRejectsHostShellEvenWithFullAccess() = runBlocking<Unit> {
        fixture { f, ai ->
            preset(ai, AiPermissionPreset.Yolo)
            val prompt = "只读分析当前项目，不执行任何命令或修改文件"
            f.model.enqueue(calls = listOf(AiCall("enter-planning", "set_task_mode", JSONObject()
                .put("mode", "readonly").put("user_request", prompt).toString())))
            f.model.enqueue(calls = listOf(call("illegal-planning-host", "printf must-not-execute")))
            f.model.enqueue("The read-only planning restriction blocked the host command")
            main { assertTrue(ai.send(prompt)) }
            await { !ai.state.value.active || ai.state.value.approval != null }
            assertNull(ai.state.value.approval)
            assertFalse(ai.state.value.active)
            assertTrue(ai.state.value.planningMode)
            assertEquals(0, f.executions.get())
            val blocked = ai.state.value.events.single { it.callId == "illegal-planning-host" }
            assertEquals(AiToolStatus.Denied, blocked.status)
            assertEquals(base.getString(R.string.ai_planning_tool_blocked), blocked.output)
            assertTrue("device_shell" in names(request(f)))
            assertFalse("device_shell" in names(request(f)))
            val messages: JSONArray = request(f).getJSONArray("messages")
            assertTrue((0 until messages.length()).map(messages::getJSONObject).any {
                it.optString("tool_call_id") == "illegal-planning-host" &&
                    it.optString("content").contains(base.getString(R.string.ai_planning_tool_blocked))
            })
        }
    }
}
