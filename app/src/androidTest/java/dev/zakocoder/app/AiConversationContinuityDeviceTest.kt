package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Own UUID histories and TLS fixture only: no commercial model, guest process or user settings. */
class AiConversationContinuityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(15000) { while (!condition()) delay(20) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading
    }
    private class Gate(val fail: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(12, TimeUnit.SECONDS)) { "Fixture gate not released" }
            if (fail) throw IOException("Fixture failure")
        }
        suspend fun waitEntered() = withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
    }
    private class History(directory: File) : AiHistoryStore(directory) {
        @Volatile var beforeSave: ((String, AiState) -> Unit)? = null
        val saving = AtomicBoolean()
        val concurrentOperation = AtomicBoolean()
        val saves = AtomicInteger()
        override fun save(projectId: String, state: AiState): List<AiConversation> {
            if (!saving.compareAndSet(false, true)) concurrentOperation.set(true)
            return try { saves.incrementAndGet(); beforeSave?.invoke(projectId, state); super.save(projectId, state) }
            finally { saving.set(false) }
        }
        override fun load(projectId: String, conversation: String?): AiHistoryStore.Loaded {
            if (saving.get()) concurrentOperation.set(true)
            return super.load(projectId, conversation)
        }
        override fun select(projectId: String, conversation: String) {
            if (saving.get()) concurrentOperation.set(true)
            super.select(projectId, conversation)
        }
        override fun delete(projectId: String, conversation: String) {
            if (saving.get()) concurrentOperation.set(true)
            super.delete(projectId, conversation)
        }
    }
    private fun controller(f: AiDeviceTest.Fixture, history: AiHistoryStore,
        root: (String) -> File = { f.root }) = AiController(context, root, { _, _ -> false },
        { _, _ -> error("No commands expected") }, {}, {}, f.transport, f.config,
        permissionStore = AiPermissionStore(context, "${f.token}-continuity-permissions"), history = history)

    @Test(timeout = 90000) fun savesSerializeWithSwitchAndDeleteAndQueuedOldSavesStayOwned() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val history = History(File(f.root, "continuity-history"))
            val project = "continuity-${UUID.randomUUID()}"
            val a = AiState(); val b = AiState()
            withContext(Dispatchers.IO) { history.save(project, b); history.save(project, a) }
            lateinit var ai: AiController
            main { ai = controller(f, history); ai.select(project) }; ready(ai)
            assertEquals(a.conversationId, ai.state.value.conversationId)
            val first = Gate()
            val blockedOnce = AtomicBoolean()
            history.beforeSave = { _, _ -> if (blockedOnce.compareAndSet(false, true)) first.hold() }
            val before = history.saves.get()
            try {
                main { ai.recommendedSkills() }; first.waitEntered()
                // This second old-session save queues behind the first; it must not capture B later.
                main { ai.recommendedSkills(); ai.conversation(b.conversationId) }
                assertTrue(ai.state.value.permissionsLoading)
                first.release.countDown()
                ready(ai)
                assertEquals(b.conversationId, ai.state.value.conversationId)
                assertEquals("Queued stale save must be dropped", before + 1, history.saves.get())
                assertFalse("Navigation must not touch the store during a save", history.concurrentOperation.get())
                assertEquals(b.conversationId, withContext(Dispatchers.IO) { history.load(project).state!!.conversationId })
            } finally { first.release.countDown() }
            main { ai.conversation(a.conversationId) }; ready(ai)
            val second = Gate()
            val nextBlockedOnce = AtomicBoolean()
            history.beforeSave = { _, _ -> if (nextBlockedOnce.compareAndSet(false, true)) second.hold() }
            try {
                main { ai.recommendedSkills() }; second.waitEntered()
                main { ai.deleteConversation(a.conversationId) }
                assertTrue(ai.state.value.permissionsLoading)
                second.release.countDown()
                ready(ai)
                assertEquals(b.conversationId, ai.state.value.conversationId)
                assertFalse(history.concurrentOperation.get())
                val reloaded = withContext(Dispatchers.IO) { history.load(project) }
                assertEquals(listOf(b.conversationId), reloaded.conversations.map { it.id })
                assertEquals(b.conversationId, reloaded.state!!.conversationId)
            } finally { second.release.countDown(); history.beforeSave = null }
        }
    }

    @Test(timeout = 90000) fun successfulCheckpointSaveClearsOnlySaveFailureAndCorruptLoadStillBlocksWrites() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val directory = File(f.root, "continuity-history")
            val history = History(directory)
            val project = "save-recovery-${UUID.randomUUID()}"
            val activeSaves = AtomicInteger()
            history.beforeSave = { _, state ->
                if (state.active && activeSaves.incrementAndGet() == 2) throw IOException("One transient fixture write failure")
            }
            lateinit var ai: AiController
            main { ai = controller(f, history); ai.select(project) }; ready(ai)
            // Keep one real local request pending through failure and subsequent periodic persistence.
            f.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"fixture reply\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                .setBodyDelay(3500, TimeUnit.MILLISECONDS))
            main { assertTrue(ai.send("Recover a transient history write")) }
            try {
                await { ai.state.value.historyError }
                await { !ai.state.value.historyError && ai.state.value.active }
                assertEquals("", ai.state.value.error)
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertFalse(ai.state.value.historyError)
                val persisted = withContext(Dispatchers.IO) { history.load(project).state!! }
                assertEquals("fixture reply", persisted.timeline.last { it.role == AiRole.Assistant }.text)
                f.enqueue("second fixture reply")
                main { assertTrue(ai.send("Continue after recovery")) }; await { !ai.state.value.active }
                assertEquals(2, f.server.requestCount)
                val corruptProject = "corrupt-${UUID.randomUUID()}"
                val corrupt = AiState()
                val file = withContext(Dispatchers.IO) {
                    history.save(corruptProject, corrupt)
                    directory.walkTopDown().single { it.name == "${corrupt.conversationId}.json" }.also { it.writeText("{broken-fixture") }
                }
                main { ai.select(corruptProject) }; await { !ai.state.value.permissionsLoading }
                assertTrue(ai.state.value.historyError)
                assertFalse(ai.canRetryHistorySave)
                main { assertNull(ai.retryHistorySave()); ai.recommendedSkills(); assertFalse(ai.send("Must not overwrite unreadable history")); ai.conversation() }
                assertEquals(2, f.server.requestCount)
                assertEquals("{broken-fixture", withContext(Dispatchers.IO) { file.readText() })
                assertTrue(ai.state.value.historyError)
            } finally {
                main { ai.stop() }; await { !ai.state.value.active }
                history.beforeSave = null
            }
        }
    }

    @Test(timeout = 90000) fun terminalSaveFailureCanRetryWithoutReplayingModelOrToolsAndReloadsWholeTurn() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val directory = File(f.root, "terminal-save-history")
            val history = History(directory)
            val project = "terminal-save-${UUID.randomUUID()}"
            val rejectTerminalSave = AtomicBoolean(true)
            val terminalFailures = AtomicInteger()
            val commands = AtomicInteger()
            history.beforeSave = { _, state ->
                if (!state.active && state.turn > 0 && rejectTerminalSave.get()) {
                    terminalFailures.incrementAndGet()
                    throw IOException("Fixture terminal write failure")
                }
            }
            withContext(Dispatchers.IO) { File(f.root, "evidence.txt").writeText("ACTUAL_LOCAL_TOOL_CONTENT") }
            lateinit var ai: AiController
            fun newController(store: AiHistoryStore) = AiController(context, { f.root }, { _, _ -> false },
                { _, _ -> commands.incrementAndGet(); error("No command should execute") }, {}, {}, f.transport, f.config,
                permissionStore = AiPermissionStore(context, "${f.token}-terminal-save-permissions"), history = store)
            main { ai = newController(history); ai.select(project) }; ready(ai)
            f.enqueue(calls = listOf(AiCall("terminal-save-read", "read_file", JSONObject().put("path", "evidence.txt").toString())))
            f.enqueue("COMPLETE_FINAL_RESPONSE_WITH_TOOL_EVIDENCE")
            main { assertTrue(ai.send("Read evidence and finish this complete turn")) }
            try {
                await { !ai.state.value.active }
                assertTrue(terminalFailures.get() > 0)
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertTrue(ai.state.value.historyError)
                assertTrue(ai.canRetryHistorySave)
                main { assertFalse(ai.send("Blocked until history is saved")); ai.conversation() }
                val expected = ai.state.value
                assertEquals(1, expected.turn)
                assertEquals(1, expected.contextTurns.size)
                assertEquals(AiToolStatus.Succeeded, expected.events.single().status)
                assertTrue(expected.events.single().output.contains("ACTUAL_LOCAL_TOOL_CONTENT"))
                assertTrue(expected.timeline.any { it.role == AiRole.Assistant && it.text == "COMPLETE_FINAL_RESPONSE_WITH_TOOL_EVIDENCE" })
                val requests = f.server.requestCount
                assertEquals(2, requests)
                val retryGate = Gate()
                rejectTerminalSave.set(false)
                val blockOnce = AtomicBoolean()
                history.beforeSave = { _, _ -> if (blockOnce.compareAndSet(false, true)) retryGate.hold() }
                var retry: Job? = null
                try {
                    main { retry = ai.retryHistorySave() }
                    assertNotNull(retry)
                    retryGate.waitEntered()
                    assertTrue("Recoverability remains observable while duplicate taps are guarded internally", ai.canRetryHistorySave)
                    main { assertNull("A second tap must not queue a stale failed snapshot", ai.retryHistorySave()) }
                    retryGate.release.countDown()
                    retry!!.join()
                } finally { retryGate.release.countDown(); retry?.cancelAndJoin(); history.beforeSave = null }
                assertFalse(ai.state.value.historyError)
                assertFalse(ai.canRetryHistorySave)
                assertEquals("", ai.state.value.error)
                assertEquals(expected.timeline, ai.state.value.timeline)
                assertEquals(requests, f.server.requestCount)
                assertEquals(0, commands.get())
                // Both the disk object and controller are new; do not reuse an in-memory history cache.
                val freshStore = AiHistoryStore(directory)
                lateinit var restored: AiController
                main { restored = newController(freshStore); restored.select(project) }; ready(restored)
                val actual = restored.state.value
                assertEquals(expected.conversationId, actual.conversationId)
                assertEquals(expected.turn, actual.turn)
                assertEquals(expected.timeline, actual.timeline)
                assertEquals(expected.contextTurns, actual.contextTurns)
                val elapsedEvents = expected.events.map { event ->
                    if (event.started != null && event.finished != null)
                        event.copy(started = 0L, finished = (event.finished - event.started).coerceAtLeast(0)) else event
                }
                assertEquals(elapsedEvents, actual.events)
                assertEquals(AiPhase.Completed, actual.phase)
                assertFalse(actual.historyError)
                assertFalse(actual.active)
                assertFalse(actual.interrupted)
                assertEquals("", actual.error)
                assertNull(actual.approval)
                main { assertNull(restored.retryHistorySave()) }
                assertEquals(requests, f.server.requestCount)
                assertEquals(0, commands.get())
                assertEquals("ACTUAL_LOCAL_TOOL_CONTENT", withContext(Dispatchers.IO) { File(f.root, "evidence.txt").readText() })
            } finally {
                history.beforeSave = null
                main { ai.stop() }; await { !ai.state.value.active }
            }
        }
    }

    @Test(timeout = 90000) fun attachmentDuringInitialProjectLoadBelongsToResolvedConversation() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val project = "initial-reference-${UUID.randomUUID()}"
            val gate = Gate()
            val history = object : AiHistoryStore(File(f.root, "initial-reference-history")) {
                override fun load(projectId: String, conversation: String?): AiHistoryStore.Loaded {
                    gate.hold()
                    return super.load(projectId, conversation)
                }
            }
            val saved = AiState()
            withContext(Dispatchers.IO) { history.save(project, saved) }
            lateinit var ai: AiController
            try {
                main { ai = controller(f, history); ai.select(project) }
                gate.waitEntered()
                main { assertTrue(ai.referenceProject(project, AiReference("build.log", "BUILD_FAILURE", "build"))) }
                gate.release.countDown()
                ready(ai)
                assertEquals(saved.conversationId, ai.state.value.conversationId)
                assertEquals("BUILD_FAILURE", ai.state.value.references.single().text)
                main { ai.conversation() }; ready(ai)
                assertTrue(ai.state.value.references.isEmpty())
                main { ai.conversation(saved.conversationId) }; ready(ai)
                assertEquals("BUILD_FAILURE", ai.state.value.references.single().text)
                assertEquals(0, f.server.requestCount)
            } finally { gate.release.countDown() }
        }
    }

    @Test(timeout = 90000) fun delayedReferenceSuccessAndFailureCannotCrossSessionOrReturnToOldGeneration() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val history = History(File(f.root, "continuity-history"))
            val project = "references-${UUID.randomUUID()}"
            val a = AiState(); val b = AiState()
            withContext(Dispatchers.IO) {
                history.save(project, b); history.save(project, a)
                File(f.root, "source.txt").writeText("OWN_REFERENCE_CONTENT")
            }
            val pending = AtomicReference<Gate?>()
            lateinit var ai: AiController
            main { ai = controller(f, history) { pending.getAndSet(null)?.hold(); f.root }; ai.select(project) }
            ready(ai)
            for ((fail, returnToA) in listOf(false to false, true to false, false to true, true to true)) {
                val gate = Gate(fail)
                var referenceJob: Job? = null
                try {
                    pending.set(gate)
                    main { referenceJob = ai.addFileReference("source.txt") }; gate.waitEntered()
                    main { ai.conversation(b.conversationId) }; ready(ai)
                    // Returning to A must still reject the old generation, not just compare IDs.
                    if (returnToA) { main { ai.conversation(a.conversationId) }; ready(ai) }
                    gate.release.countDown()
                    referenceJob!!.join()
                    assertTrue(ai.state.value.references.isEmpty())
                    assertEquals("", ai.state.value.error)
                } finally { gate.release.countDown(); referenceJob?.cancelAndJoin() }
                main { ai.conversation(a.conversationId) }; ready(ai)
            }
            var sameSession: Job? = null
            main { sameSession = ai.addFileReference("source.txt") }; sameSession!!.join()
            assertEquals("OWN_REFERENCE_CONTENT", ai.state.value.references.single().text)
            assertEquals(0, f.server.requestCount)
        }
    }
}
