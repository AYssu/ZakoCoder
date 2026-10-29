package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiChangesReadTest {
    private val state = AiState(projectId = "own-project", conversationId = "own-conversation", turn = 2)
    private val record = AiCheckpoint("checkpoint", 1, 2, 1, false, true, false, state.conversationId)

    @Test fun persistedChangesDoNotDependOnVolatileUndoRecords() = runBlocking {
        val read = AiChangesRead()
        val change = AiCheckpointChange("src/main.py", "before", "after", beforeMode = "rw-r--r--", afterMode = "rwxr-xr-x")
        read.load(state, 2, { listOf(record) }, { assertEquals(record.id, it); listOf(change) })
        assertEquals(AiChangesStatus.Ready, read.snapshot.status)
        assertEquals(listOf(change), read.snapshot.changes)
        assertTrue(state.changes.isEmpty())
    }

    @Test fun missingFailureAndActualEmptyAreDistinct() = runBlocking {
        val missing = AiChangesRead()
        missing.load(state, 2, { listOf(record.copy(conversation = "other")) }, { error("must not load another conversation") })
        assertEquals(AiChangesStatus.Missing, missing.snapshot.status)
        val failed = AiChangesRead()
        failed.load(state, 2, { listOf(record) }, { error("corrupt private blob") })
        assertEquals(AiChangesStatus.Failed, failed.snapshot.status)
        assertTrue(failed.snapshot.changes.isEmpty())
        val empty = AiChangesRead()
        empty.load(state, 2, { listOf(record.copy(changed = 0)) }, { emptyList() })
        assertEquals(AiChangesStatus.Ready, empty.snapshot.status)
        assertTrue(empty.snapshot.changes.isEmpty())
    }

    @Test fun unsealedRunningRestoringAndLoadingDoNotReadBodiesOrClaimEmpty() = runBlocking {
        for ((s, r, expected) in listOf(
            Triple(state, record.copy(sealed = false), AiChangesStatus.Unsealed),
            Triple(state.copy(active = true), record, AiChangesStatus.Running),
            Triple(state.copy(restoring = true), record, AiChangesStatus.Restoring)
        )) {
            val read = AiChangesRead()
            read.load(s, 2, { listOf(r) }, { error("body must not be sampled") })
            assertEquals(expected, read.snapshot.status)
        }
        val loading = AiChangesRead()
        loading.load(state.copy(permissionsLoading = true), 2, { error("must wait for session load") }, { error("must wait") })
        assertEquals(AiChangesStatus.Loading, loading.snapshot.status)
    }

    @Test fun replacementIdentityStaysEmptyWhileOldReadCompletesAndCancellationCannotPublish() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = AiChangesRead()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            old.load(state, 2, { listOf(record) }, {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await(); listOf(AiCheckpointChange("old.py", "a", "b")) }
            })
        }
        entered.await()
        val replacement = AiChangesRead()
        assertEquals(AiChangesStatus.Loading, replacement.snapshot.status)
        assertTrue(replacement.snapshot.changes.isEmpty())
        job.cancel(); release.complete(Unit); job.join()
        assertEquals(AiChangesStatus.Loading, old.snapshot.status)
        assertTrue(replacement.snapshot.changes.isEmpty())
    }
}
