package dev.zakocoder.app.diagnostics

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticEventsFlushTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun flushDrainsAcceptedEventsAndAlsoCompletesForAnEmptyQueue() = runBlocking {
        val logger = DiagnosticEvents.forTests(temporary.newFolder())
        assertTrue(logger.flush())

        val id = DiagnosticId.create()
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, id))
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Succeeded, id))
        assertTrue(logger.flush())

        val outcomes = logger.exportSnapshot().jsonl.lineSequence()
            .filter { it.isNotEmpty() }
            .map { JSONObject(it).getString("outcome") }
            .toList()
        assertEquals(listOf("Started", "Succeeded"), outcomes)
    }
}
