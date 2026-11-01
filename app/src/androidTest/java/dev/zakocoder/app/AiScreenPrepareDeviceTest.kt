package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.screentest.ScreenTestApk
import dev.zakocoder.app.screentest.ScreenTestApkProposal
import dev.zakocoder.app.screentest.ScreenTestResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real local TLS tool turns with isolated files and fake APK metadata; no system grants or installation. */
class AiScreenPrepareDeviceTest {
    @Before fun foreground() = warmDeviceActivity()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val project = "screen-prepare-project"
    private val apkPath = "app/build/outputs/apk/debug/app-debug.apk"
    private val digest = "a".repeat(64)
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private fun call(action: String, id: String = action) = AiCall("screen-$id", "android_test",
        JSONObject().put("action", action).put("reason", "Check the current project debug build")
            .apply { if (action == "prepare") put("path", apkPath) }.toString())

    private inner class Harness(val f: AiDeviceTest.Fixture) {
        val lease = AtomicReference<String?>(null)
        val previews = AtomicInteger()
        val selections = AtomicInteger()
        val executed = CopyOnWriteArrayList<Pair<String, String?>>()
        val finished = CopyOnWriteArrayList<String>()
        lateinit var ai: AiController
        private var pendingFailure: Throwable? = null
        private fun diagnostics(): String {
            if (!::ai.isInitialized) return "controllerInitialized=false"
            val state = ai.state.value
            return "active=${state.active}, phase=${state.phase}, configured=${state.configured}, " +
                "permissionsLoading=${state.permissionsLoading}, permissionsSaving=${state.permissionsSaving}, " +
                "skillsLoading=${state.skillsLoading}, modelsLoading=${ai.models.state.value.loading}, " +
                "approval=${state.approval?.eventId}, events=${state.events.map { it.tool to it.status }}, " +
                "requests=${f.server.requestCount}, previews=${previews.get()}, selections=${selections.get()}, " +
                "leasePresent=${lease.get() != null}, failure=${state.failureKind}"
        }
        suspend fun await(label: String, condition: () -> Boolean) {
            try {
                withTimeout(45_000) { while (!condition()) delay(10) }
            } catch (timeout: TimeoutCancellationException) {
                val failure = AssertionError("Timed out waiting for $label: ${diagnostics()}", timeout)
                pendingFailure = failure
                throw failure
            } catch (failure: Throwable) {
                pendingFailure = failure
                throw failure
            }
        }
        suspend fun start(permission: AiPermission = AiPermission.Allow) {
            val isolated = object : ContextWrapper(base) {
                override fun getFilesDir() = File(f.root, "controller-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "controller-private").apply { mkdirs() }
            }
            main {
                ai = AiController(isolated, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> error("This fixture must not execute commands") }, {}, {}, f.transport, f.config,
                    history = f.history,
                    capabilitySnapshot = { mapOf(CapabilityPackage.Screen to "screen-package-test") },
                    screenTestPreview = { id, path ->
                        assertEquals(project, id); assertEquals(apkPath, path)
                        previews.incrementAndGet()
                        ScreenTestApkProposal(ScreenTestApk(path, "dev.zakocoder.fixture", "Fixture", "1", true), digest)
                    },
                    screenTestSelect = { id, path, expectedDigest ->
                        assertEquals(project, id); assertEquals(apkPath, path); assertEquals(digest, expectedDigest)
                        lease.set("approved-lease-${selections.incrementAndGet()}")
                    },
                    screenTestTool = { id, args, approvedLease ->
                        assertEquals(project, id)
                        val action = args.getString("action")
                        executed += action to approvedLease
                        if (action == "status") {
                            assertNull(approvedLease)
                            ScreenTestResult(JSONObject().put("selected", false).put("targetSelectionRequired", true)
                                .put("candidates", JSONArray().put(JSONObject().put("path", apkPath)
                                    .put("package", "dev.zakocoder.fixture").put("debuggable", true))).toString())
                        } else {
                            assertEquals("launch", action)
                            assertEquals(lease.get(), approvedLease)
                            ScreenTestResult("{\"launchRequested\":true,\"verified\":false}")
                        }
                    },
                    screenTestLease = { id -> lease.get().takeIf { id == project } },
                    finishScreenTestTask = { id, owned ->
                        assertEquals(project, id)
                        if (owned != null) { finished += owned; lease.compareAndSet(owned, null) }
                    },
                    stopScreenTest = { lease.set(null) })
                ai.select(project)
            }
            await("controller ready") { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.models.state.value.loading }
            main { ai.setPolicy(AiPolicy(overrides = mapOf(AiTool.AndroidTest to permission))) }
            await("permission policy saved") { !ai.state.value.permissionsSaving }
        }
        suspend fun send() { main { assertTrue(ai.send("Discover the debug APK and verify the app")) } }
        suspend fun waitForApproval() = await("target approval") { ai.state.value.approval != null }
        suspend fun done() = await("task completion") { !ai.state.value.active }
        fun rememberFailure(failure: Throwable) { pendingFailure = failure }
        suspend fun stop() {
            if (!::ai.isInitialized) return
            val prior = pendingFailure
            try {
                withContext(NonCancellable) {
                    main { ai.stop() }
                    withTimeout(10_000) { while (ai.state.value.active) delay(10) }
                }
            } catch (cleanupFailure: Throwable) {
                val failure = AssertionError("Cleanup did not finish: ${diagnostics()}", cleanupFailure)
                if (prior != null) prior.addSuppressed(failure)
                else throw failure
            }
        }

    }

    @Test(timeout = 120000) fun statusFindsCandidatesThenPrepareStillAsksUnderAllowAndLaunchUsesNewLease() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("status")))
                f.enqueue(calls = listOf(call("prepare"), call("launch")))
                f.enqueue("Target prepared with approval and launch requested; UI validation remains pending.")
                h.send(); h.waitForApproval()
                assertEquals(listOf("status" to null), h.executed.toList())
                assertEquals(1, h.previews.get()); assertEquals(0, h.selections.get()); assertNull(h.lease.get())
                val detail = h.ai.state.value.approval!!.detail
                assertTrue(detail.contains("dev.zakocoder.fixture")); assertTrue(detail.contains(apkPath)); assertTrue(detail.contains(digest))
                main { h.ai.approve(AiDecision.Once) }; h.done()
                assertEquals(1, h.selections.get())
                assertEquals(listOf("status" to null, "launch" to "approved-lease-1"), h.executed.toList())
                assertEquals(listOf("approved-lease-1"), h.finished.toList())
                assertTrue(h.ai.state.value.events.all { it.status == AiToolStatus.Succeeded })
                assertEquals(3, f.history.load(project).state!!.events.size)
            } catch (failure: Throwable) { h.rememberFailure(failure); throw failure } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun rejectionDoesNotSelectTargetOrExecuteScreenAction() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("prepare"))); f.enqueue("Target selection was rejected.")
                h.send(); h.waitForApproval()
                main { h.ai.approve(AiDecision.Reject) }; h.done()
                assertEquals(1, h.previews.get()); assertEquals(0, h.selections.get())
                assertTrue(h.executed.isEmpty()); assertTrue(h.finished.isEmpty()); assertNull(h.lease.get())
                assertEquals(AiToolStatus.Denied, h.ai.state.value.events.single().status)
            } catch (failure: Throwable) { h.rememberFailure(failure); throw failure } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun denyRuleWinsBeforeReadingOrPreviewingApk() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start(AiPermission.Deny)
            try {
                f.enqueue(calls = listOf(call("prepare"))); f.enqueue("Screen tool is denied.")
                h.send(); h.done()
                assertNull(h.ai.state.value.approval)
                assertEquals(0, h.previews.get()); assertEquals(0, h.selections.get()); assertTrue(h.executed.isEmpty())
                assertEquals(AiToolStatus.Denied, h.ai.state.value.events.single().status)
            } catch (failure: Throwable) { h.rememberFailure(failure); throw failure } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun replacingTargetWhileApprovalIsVisibleInvalidatesProposal() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("prepare"))); f.enqueue("Target changed; proposal was not applied.")
                h.send(); h.waitForApproval()
                main { h.lease.set("replacement-user-selection"); h.ai.approve(AiDecision.Once) }; h.done()
                assertEquals(0, h.selections.get()); assertTrue(h.executed.isEmpty()); assertTrue(h.finished.isEmpty())
                assertEquals("replacement-user-selection", h.lease.get())
                assertEquals(AiToolStatus.Failed, h.ai.state.value.events.single().status)
                assertTrue(h.ai.state.value.events.single().output.contains(base.getString(R.string.ai_screen_test_session_changed)))
            } catch (failure: Throwable) { h.rememberFailure(failure); throw failure } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun stoppingAtApprovalCancelsWithoutSelectingOrLaunching() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("prepare")))
                h.send(); h.waitForApproval(); h.stop()
                assertEquals(1, h.previews.get()); assertEquals(0, h.selections.get())
                assertTrue(h.executed.isEmpty()); assertTrue(h.finished.isEmpty()); assertNull(h.ai.state.value.approval)
                assertEquals(AiToolStatus.Cancelled, h.ai.state.value.events.single().status)
                assertEquals(AiToolStatus.Cancelled, f.history.load(project).state!!.events.single().status)
            } catch (failure: Throwable) { h.rememberFailure(failure); throw failure } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun taskToolGrantDoesNotSkipAnotherTargetPreparationConfirmation() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("prepare", "prepare-first"), call("prepare", "prepare-second")))
                f.enqueue("Only the first explicitly approved preparation was applied.")
                h.send(); h.waitForApproval()
                val firstEvent = h.ai.state.value.approval!!.eventId
                main { h.ai.approve(AiDecision.TaskTool) }
                h.await("second distinct target approval") { h.ai.state.value.approval?.eventId?.let { it != firstEvent } == true }
                assertEquals(2, h.previews.get()); assertEquals(1, h.selections.get())
                main { h.ai.approve(AiDecision.Reject) }; h.done()
                assertEquals(1, h.selections.get())
                assertEquals(listOf(AiToolStatus.Succeeded, AiToolStatus.Denied), h.ai.state.value.events.map { it.status })
            } catch (failure: Throwable) { h.rememberFailure(failure); throw failure } finally { h.stop() }
        }
    }
}
