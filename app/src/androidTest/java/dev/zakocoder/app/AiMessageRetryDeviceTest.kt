package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AiMessageRetryDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun foreground() = warmDeviceActivity()
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(test: () -> Boolean) = withTimeout(20000) { while (!test()) delay(25) }
    private fun controller(f: AiDeviceTest.Fixture, history: AiHistoryStore = f.history,
        canStartTask: () -> Boolean = { true }): AiController {
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
        }
        return AiController(context, { File(f.root, "project-$it").apply { mkdirs() } },
            { _, _ -> false }, { _, _ -> error("Unexpected shell") }, {}, {}, f.transport, f.config, history = history, canStartTask = canStartTask)
    }
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading &&
        !ai.state.value.permissionsSaving && !ai.models.state.value.loading && !ai.state.value.skillsLoading }
    private suspend fun send(ai: AiController, text: String) {
        main { assertTrue(ai.send(text)) }
        await { !ai.state.value.active }; assertEquals(AiPhase.Completed, ai.state.value.phase)
    }
    @Test(timeout = 90000) fun retryBranchesBeforeSelectedTurnAndPreservesOriginalAndCurrentFiles() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("a") }; ready(ai)
            f.enqueue("first answer"); send(ai, "first input")
            f.enqueue("old second answer"); send(ai, "second input")
            val original = ai.state.value.conversationId
            val file = File(f.root, "project-a/source.txt").apply { writeText("user current content") }
            f.enqueue("new second answer")
            assertTrue(ai.retryTurn("a", original, 2))
            await { !ai.state.value.active }
            val state = ai.state.value
            assertNotEquals(original, state.conversationId)
            assertEquals(2, state.turn)
            assertEquals(listOf("first input", "second input"), state.timeline.filter { it.role == AiRole.User }.map { it.text })
            assertFalse(state.timeline.any { it.text == "old second answer" })
            assertTrue(state.timeline.any { it.text == "new second answer" })
            assertEquals("user current content", file.readText())
            val saved = withContext(Dispatchers.IO) { f.history.load("a", original).state!! }
            assertTrue(saved.timeline.any { it.text == "old second answer" })
            assertEquals(3, f.server.requestCount)
        }
    }
    @Test(timeout = 90000) fun editedRetryUsesReplacementAndRejectsStaleOwnerAndOversizedInputWithoutFork() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("a") }; ready(ai)
            f.enqueue("answer"); send(ai, "original")
            val original = ai.state.value.conversationId
            assertFalse(ai.retryTurn("other", original, 1))
            assertFalse(ai.retryTurn("a", "stale", 1))
            assertFalse(ai.retryTurn("a", original, 1, "x".repeat(16001)))
            assertEquals(original, ai.state.value.conversationId)
            f.enqueue("edited answer")
            assertTrue(ai.retryTurn("a", original, 1, "edited input"))
            await { !ai.state.value.active }
            assertEquals("edited input", ai.state.value.timeline.single { it.role == AiRole.User }.text)
            assertEquals(2, f.server.requestCount)
            val saved = withContext(Dispatchers.IO) { f.history.load("a", original).state!! }
            assertEquals("original", saved.timeline.single { it.role == AiRole.User }.text)
        }
    }
    @Test(timeout = 90000) fun retryPreservesLongReferencesAndOriginalInputAfterHistoryReload() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("a") }; ready(ai)
            val references = (1..3).map { AiReference("file-$it.kt", "x".repeat(6000), startLine = it, unsaved = true) }
            main { references.forEach { assertTrue(ai.addReference(it)) } }
            f.enqueue("answer"); send(ai, "review these")
            val original = ai.state.value.conversationId
            val saved = withContext(Dispatchers.IO) { f.history.load("a", original).state!! }
            val user = saved.timeline.single { it.role == AiRole.User }
            assertTrue(user.text.length > 16000)
            assertEquals("review these", user.originalInput)
            assertEquals(references, user.inputReferences)
            f.enqueue("retried answer")
            assertTrue(ai.retryTurn("a", original, 1))
            await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            val retry = ai.state.value.timeline.single { it.role == AiRole.User }
            assertEquals(user.text, retry.text)
            assertEquals(references, retry.inputReferences)
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test(timeout = 90000) fun retryBecomingBusyDuringForkSaveRestoresOriginalSelectionWithoutAnExtraBranchOrRequest() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(false)
            val blocked = AtomicBoolean(false)
            val allowed = AtomicBoolean(true)
            val originalId = AtomicReference<String>()
            val unpublishedFork = AtomicReference<String>()
            val history = object : AiHistoryStore(File(f.root, "retry-controlled-history")) {
                override fun save(projectId: String, state: AiState): List<AiConversation> {
                    val saved = super.save(projectId, state)
                    // Persist a real fork first, then hold publication while another operation becomes busy.
                    if (armed.get() && state.conversationId != originalId.get() && blocked.compareAndSet(false, true)) {
                        unpublishedFork.set(state.conversationId)
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "Fork test release timed out" }
                    }
                    return saved
                }
            }
            lateinit var ai: AiController
            main { ai = controller(f, history, allowed::get); ai.select("a") }; ready(ai)
            f.enqueue("original answer"); send(ai, "original input")
            val original = ai.state.value
            originalId.set(original.conversationId)
            val before = withContext(Dispatchers.IO) { history.load("a") }
            assertEquals(listOf(original.conversationId), before.conversations.map { it.id })
            // A regression can finish promptly and fail the no-request assertion, rather than waiting on an empty server queue.
            f.enqueue("unexpected retry answer")
            var retry: Deferred<Boolean>? = null
            try {
                armed.set(true)
                val pending = async { ai.retryTurn("a", original.conversationId, 1) }
                retry = pending
                withContext(Dispatchers.IO) { assertTrue("Retry must reach the persisted-fork boundary", entered.await(15, TimeUnit.SECONDS)) }
                assertNotEquals(original.conversationId, unpublishedFork.get())
                assertEquals("The uncommitted fork must not replace the displayed owner", original.conversationId, ai.state.value.conversationId)
                assertTrue(ai.state.value.permissionsLoading)
                allowed.set(false)
                release.countDown()
                assertFalse("Busy publication guard must reject retry", withTimeout(20000) { pending.await() })
                await { !ai.state.value.permissionsLoading && !ai.state.value.active }
                assertEquals(original.conversationId, ai.state.value.conversationId)
                assertEquals(original.turn, ai.state.value.turn)
                assertEquals(original.timeline, ai.state.value.timeline)
                assertEquals(1, f.server.requestCount)
                val after = withContext(Dispatchers.IO) { history.load("a") }
                assertEquals("Persisted selected pointer returns to the original", original.conversationId, after.state!!.conversationId)
                assertEquals("The temporary fork is deleted, not retained as an extra conversation",
                    before.conversations.map { it.id }.toSet(), after.conversations.map { it.id }.toSet())
                assertFalse(after.conversations.any { it.id == unpublishedFork.get() })
                assertEquals(original.timeline, after.state!!.timeline)
            } finally {
                release.countDown()
                retry?.cancelAndJoin()
                main { ai.stop() }
                await { !ai.state.value.active && !ai.state.value.permissionsLoading }
            }
        }
    }

}
