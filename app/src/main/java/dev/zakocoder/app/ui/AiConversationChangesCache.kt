package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.AiCheckpoint
import dev.zakocoder.app.ai.AiCheckpointChange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One conversation/revision snapshot, shared by visible turn cards. No process-global history. */
internal class AiConversationChangesCache(
    private val readCheckpoints: suspend () -> List<AiCheckpoint>,
    private val readChanges: suspend (String) -> List<AiCheckpointChange>,
) {
    private val metadataLock = Mutex()
    private val changesLock = Mutex()
    private var checkpoints: List<AiCheckpoint>? = null
    private val changes = linkedMapOf<String, List<AiCheckpointChange>>()

    suspend fun checkpoints(): List<AiCheckpoint> = metadataLock.withLock {
        checkpoints ?: readCheckpoints().also { checkpoints = it }
    }

    suspend fun changes(key: String): List<AiCheckpointChange> = changesLock.withLock {
        changes.remove(key)?.let { value -> changes[key] = value; return@withLock value }
        readChanges(key).also { value ->
            // Keep at most four bounded checkpoint bodies; scrolling does not retain all source history.
            changes[key] = value
            while (changes.size > 4) changes.remove(changes.keys.first())
        }
    }
}
