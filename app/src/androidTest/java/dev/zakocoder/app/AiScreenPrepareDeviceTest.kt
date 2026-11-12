package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.screentest.ScreenTestResult
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real controller/TLS approvals with isolated storage; never captures or operates a real screen. */
class AiScreenPrepareDeviceTest {
    @Before fun foreground() = warmDeviceActivity()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val project = "device-screen-task"
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(10) }
    private fun call(action: String, id: String = action) = AiCall("screen-$id", "android_test",
        JSONObject().put("action", action).put("reason", "Observe the screen requested by the user").toString())

    private inner class Harness(val f: AiDeviceTest.Fixture) {
        var lease: String? = null
        var afterOperation: (String) -> Unit = {}
        val taskIds = mutableListOf<String>()
        val executed = mutableListOf<Pair<String, String?>>()
        val finished = mutableListOf<String>()
        lateinit var ai: AiController
        suspend fun start(permission: AiPermission = AiPermission.Allow) {
            val isolated = object : ContextWrapper(base) {
                override fun getFilesDir() = File(f.root, "screen-controller-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "screen-controller-private").apply { mkdirs() }
            }
            main {
                ai = AiController(isolated, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> error("No shell in this fixture") }, {}, {}, f.transport, f.config, history = f.history,
                    capabilitySnapshot = { mapOf(CapabilityPackage.Screen to "global-screen-generation") },
                    screenTestAuthorize = { id, taskId ->
                        assertEquals(project, id); assertFalse(taskIds.contains(taskId))
                        taskIds += taskId
                        "screen-lease-${taskIds.size}".also { lease = it }
                    },
                    screenTestTool = { id, args, approved ->
                        assertEquals(project, id)
                        val action = args.getString("action")
                        if (action == "status") assertNull(approved) else assertEquals(lease, approved)
                        executed += action to approved
                        afterOperation(action)
                        ScreenTestResult("{\"observed\":false,\"fixture\":true}")
                    },
                    screenTestLease = { lease },
                    finishScreenTestTask = { _, owned -> if (owned != null) { finished += owned; if (owned == lease) lease = null } },
                    stopScreenTest = { lease = null })
                ai.select(project)
            }
            await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.models.state.value.loading }
            main { ai.setPolicy(AiPolicy(overrides = mapOf(AiTool.AndroidTest to permission))) }
            await { !ai.state.value.permissionsSaving }
        }
        suspend fun send() { main { assertTrue(ai.send("Observe the relevant current-device screen")) } }
        suspend fun approval() = await { ai.state.value.approval != null }
        suspend fun done() = await { !ai.state.value.active }
        suspend fun stop() { main { ai.stop() }; done() }
    }

    @Test(timeout = 120000) fun statusDoesNotReadScreenAndFirstActualOperationAsksEvenWithAllow() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("status")))
                f.enqueue(calls = listOf(call("observe"), call("back")))
                f.enqueue("Actual screen results remain fixture evidence")
                h.send(); h.approval()
                assertEquals(listOf("status" to null), h.executed)
                assertTrue(h.taskIds.isEmpty())
                assertTrue(h.ai.state.value.approval!!.detail.contains("当前设备屏幕"))
                main { h.ai.approve(AiDecision.Once) }; h.done()
                assertEquals(1, h.taskIds.size)
                assertEquals(listOf("status" to null, "observe" to "screen-lease-1", "back" to "screen-lease-1"), h.executed)
                assertEquals(listOf("screen-lease-1"), h.finished)
                assertNull(h.lease)
            } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun explicitAuthorizeOnlyGrantsAfterConsentAndNeverReadsScreen() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("authorize"))); f.enqueue("Device consent recorded")
                h.send(); h.approval()
                assertTrue(h.executed.isEmpty()); assertTrue(h.taskIds.isEmpty())
                main { h.ai.approve(AiDecision.TaskTool) }; h.done()
                assertEquals(1, h.taskIds.size); assertTrue(h.executed.isEmpty()); assertNull(h.lease)
                assertEquals(AiToolStatus.Succeeded, h.ai.state.value.events.single().status)
            } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun rejectionAndDenialNeverAuthorizeOrReadScreen() = runBlocking {
        for (permission in listOf(AiPermission.Allow, AiPermission.Deny)) AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start(permission)
            try {
                f.enqueue(calls = listOf(call("observe"))); f.enqueue("No device operation")
                h.send()
                if (permission != AiPermission.Deny) { h.approval(); main { h.ai.approve(AiDecision.Reject) } }
                h.done(); assertTrue(h.taskIds.isEmpty()); assertTrue(h.executed.isEmpty()); assertTrue(h.finished.isEmpty())
                assertEquals(AiToolStatus.Denied, h.ai.state.value.events.single().status)
            } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun changingLeaseDuringConsentDoesNotAdoptReplacement() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("observe"))); f.enqueue("Replacement kept")
                h.send(); h.approval()
                main { h.lease = "manual-task"; h.ai.approve(AiDecision.Once) }; h.done()
                assertTrue(h.taskIds.isEmpty()); assertTrue(h.executed.isEmpty()); assertTrue(h.finished.isEmpty())
                assertEquals("manual-task", h.lease)
                assertEquals(AiToolStatus.Failed, h.ai.state.value.events.single().status)
            } finally { h.stop() }
        }
    }

    @Test(timeout = 120000) fun stoppingAtConsentNeverCreatesLease() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("observe")))
                h.send(); h.approval(); h.stop()
                assertTrue(h.taskIds.isEmpty()); assertTrue(h.executed.isEmpty()); assertNull(h.ai.state.value.approval)
                assertEquals(AiToolStatus.Cancelled, h.ai.state.value.events.single().status)
            } finally { h.stop() }
        }
    }

    @Test(timeout = 180000) fun laterTaskRequiresNewConsentAndNeverReusesPreviousLease() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                repeat(2) { index ->
                    f.enqueue(calls = listOf(call("observe", "observe-$index"))); f.enqueue("Round done")
                    h.send(); h.approval()
                    assertEquals(index, h.taskIds.size)
                    main { h.ai.approve(AiDecision.TaskTool) }; h.done()
                    assertNull(h.lease)
                }
                assertEquals(2, h.taskIds.distinct().size)
                assertEquals(listOf("screen-lease-1", "screen-lease-2"), h.finished)
            } finally { h.stop() }
        }
    }
    @Test(timeout = 120000) fun revocationBetweenOperationsDoesNotCreateNewSilentGrant() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                h.afterOperation = { if (it == "observe") h.lease = null }
                f.enqueue(calls = listOf(call("observe"), call("back")))
                f.enqueue("Device grant was revoked")
                h.send(); h.approval(); main { h.ai.approve(AiDecision.TaskTool) }; h.done()
                assertEquals(1, h.taskIds.size)
                assertEquals(listOf("observe" to "screen-lease-1"), h.executed)
                assertEquals(listOf(AiToolStatus.Succeeded, AiToolStatus.Failed), h.ai.state.value.events.map { it.status })
            } finally { h.stop() }
        }
    }

    @Test(timeout = 180000) fun waitingForAnswerRevokesLeaseAndContinuationRequiresConsentAgain() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val h = Harness(f); h.start()
            try {
                f.enqueue(calls = listOf(call("observe")))
                f.enqueue(calls = listOf(AiCall("screen-question", "ask_user", JSONObject().put("question", "Continue screen task?").toString())))
                h.send(); h.approval(); main { h.ai.approve(AiDecision.TaskTool) }; h.done()
                assertEquals(AiPhase.AwaitingAnswer, h.ai.state.value.phase)
                assertNull(h.lease)
                assertEquals(listOf("screen-lease-1"), h.finished)
                val pending = h.ai.state.value
                f.enqueue(calls = listOf(call("observe", "after-answer"))); f.enqueue("Task resumed")
                main { assertTrue(h.ai.answerQuestion(project, pending.conversationId, pending.questions.single().id, "Continue")) }
                h.approval()
                assertEquals(1, h.taskIds.size)
                main { h.ai.approve(AiDecision.Once) }; h.done()
                assertEquals(2, h.taskIds.distinct().size)
                assertEquals(listOf("screen-lease-1", "screen-lease-2"), h.finished)
                assertNull(h.lease)
            } finally { h.stop() }
        }
    }
}
