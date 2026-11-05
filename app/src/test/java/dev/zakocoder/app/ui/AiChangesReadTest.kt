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

    @Test fun activeCurrentTaskDoesNotBlockSealedHistoricalTurn() = runBlocking {
        val read = AiChangesRead()
        val historical = AiCheckpointChange("history.py", "before", "after")
        read.load(state.copy(active = true, turn = 3), 2, { listOf(record) }, {
            assertEquals(record.id, it)
            listOf(historical)
        })
        assertEquals(AiChangesStatus.Ready, read.snapshot.status)
        assertEquals(listOf(historical), read.snapshot.changes)
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

    private fun writeRecord(path: String, before: String, after: String, turn: Int = 1): AiChange {
        fun baseline(text: String) = dev.zakocoder.app.editor.FileBaseline("own-project", path, text, "fixture-digest", false)
        return AiChange(path, baseline(before), baseline(after), turn = turn)
    }

    private fun event(id: String, turn: Int, tool: AiTool, path: String = "file.py", status: AiToolStatus = AiToolStatus.Succeeded) =
        AiToolEvent(id, "call-$id", tool, tool.wire, "{}", "", path, status = status, turn = turn)

    @Test fun sealedCheckpointDoesNotInheritPreviousTurnOrTransientFileCounts() {
        val old = writeRecord("file.py", "old", "previous-turn")
        val owned = state.copy(changes = listOf(old),
            commandChanges = listOf(AiCommandChange("old-command:old.py", "old.py", "a", "b")),
            events = listOf(event("old-write", 1, AiTool.WriteFile), event("old-command", 1, AiTool.RunCommand)))
        assertTrue(aiChangeRowsForTurn(owned, 2, AiChangesSnapshot(AiChangesStatus.Ready, checkpoint = record)).isEmpty())
        val checkpointChange = AiCheckpointChange("actual.py", "actual-before", "actual-after")
        assertEquals(listOf("actual.py"), aiChangeRowsForTurn(owned, 2,
            AiChangesSnapshot(AiChangesStatus.Ready, checkpoint = record, changes = listOf(checkpointChange))).map { it.path })
    }

    @Test fun failedOrRunningReadOnlyUsesCommandEventsOwnedByTheSelectedTurn() {
        val owned = state.copy(active = true,
            commandChanges = listOf(AiCommandChange("old:old.py", "old.py", "a", "b"),
                AiCommandChange("current:current.py", "current.py", "before", "after")),
            events = listOf(event("old", 1, AiTool.RunCommand), event("current", 2, AiTool.RunCommand)))
        for (status in listOf(AiChangesStatus.Failed, AiChangesStatus.Running, AiChangesStatus.Unsealed)) {
            assertEquals(listOf("current.py"), aiChangeRowsForTurn(owned, 2, AiChangesSnapshot(status)).map { it.path })
        }
        assertTrue(aiChangeRowsForTurn(owned, 1, AiChangesSnapshot(AiChangesStatus.Failed)).isEmpty())
    }

    @Test fun currentWriteUsesItsOwnTurnAndQueuedWriteCannotBorrowOldUndo() {
        val old = writeRecord("file.py", "old", "previous-turn")
        val current = writeRecord("file.py", "previous-turn", "current-turn", turn = 2)
        val owned = state.copy(changes = listOf(old, current),
            events = listOf(event("old", 1, AiTool.WriteFile), event("current", 2, AiTool.WriteFile)))
        val row = aiChangeRowsForTurn(owned, 2, AiChangesSnapshot(AiChangesStatus.Running)).single()
        assertEquals("previous-turn", row.before)
        assertEquals("current-turn", row.after)
        val queued = owned.copy(changes = listOf(old), events = listOf(event("current", 2, AiTool.WriteFile, status = AiToolStatus.Running)))
        assertTrue(aiChangeRowsForTurn(queued, 2, AiChangesSnapshot(AiChangesStatus.Running)).isEmpty())
        assertTrue(aiCurrentTurnEditorChanges(queued).isEmpty())
    }


    @Test fun undoRemovingCurrentWriteCannotReattachPreviousOrUnknownTurnRecords() {
        val old = writeRecord("file.py", "old", "previous-turn", turn = 1)
        val unknown = writeRecord("unknown.py", "a", "b", turn = 0)
        val current = writeRecord("file.py", "previous-turn", "current-turn", turn = 2)
        val owned = state.copy(changes = listOf(old, unknown, current),
            events = listOf(event("completed-current", 2, AiTool.WriteFile)))
        assertEquals(listOf(current), aiCurrentTurnEditorChanges(owned))
        val undone = owned.copy(changes = owned.changes - current)
        assertTrue(aiCurrentTurnEditorChanges(undone).isEmpty())
        assertTrue(aiChangeRowsForTurn(undone, 2, AiChangesSnapshot(AiChangesStatus.Running)).isEmpty())
        assertTrue(aiCurrentTurnEditorChanges(owned.copy(turn = 0)).isEmpty())
    }

}
