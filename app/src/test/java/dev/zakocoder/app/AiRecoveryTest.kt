package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiRecoveryTest {
    @Test fun recoveryKeepsOriginalIntentAndExecutedEvidenceWithoutInventingSuccess() {
        val state = AiState(turn = 2,
            timeline = listOf(AiEntry("old", "old request", role = AiRole.User, turn = 1), AiEntry("q", "fix current code", role = AiRole.User, turn = 2),
                AiEntry("a", "partly explained", role = AiRole.Assistant, turn = 2), AiEntry("e", toolId = "e", turn = 2)),
            events = listOf(AiToolEvent("e", "call", AiTool.RunCommand, "run_command", "{command: sed}", "", "sed", status = AiToolStatus.Succeeded, output = "verified output", exitCode = 0)),
            requests = listOf(AiRequestLog(turn = 2, round = 2, status = 200, diagnostic = "missing_done", failure = AiFailure.Protocol)))
        val note = recoveryNote(state, AiFailure.Protocol)
        assertTrue(note.contains("fix current code")); assertFalse(note.contains("old request"))
        assertTrue(note.contains("partly explained")); assertTrue(note.contains("verified output")); assertTrue(note.contains("missing_done"))
        assertTrue(note.contains("status=Succeeded")); assertTrue(note.contains("Do not assume completion or replay actions"))
    }
    @Test fun trimmedTimelineStillRetainsTaskPromptAndCurrentToolEvidence() {
        val state = AiState(turn = 7, taskPrompt = "original intent survived trimming",
            events = listOf(AiToolEvent("kept", "c", AiTool.RunCommand, "run_command", "{}", "", "sed", status = AiToolStatus.Succeeded, output = "already applied", turn = 7)))
        val note = recoveryNote(state, AiFailure.Network)
        assertTrue(note.contains("original intent survived trimming")); assertTrue(note.contains("already applied"))
    }
    @Test fun retentionKeepsLatestEvidenceAndExplicitlyMarksTruncation() {
        val huge = AiState(turn = 1, timeline = listOf(AiEntry("q", "q".repeat(16000), role = AiRole.User, turn = 1)) +
            (1..10).map { AiEntry("a$it", "output-$it " + "x".repeat(64000), role = AiRole.Assistant, turn = 1) })
        val note = recoveryNote(huge, AiFailure.Interrupted)
        assertTrue(note.length <= 24000); assertTrue(note.contains("truncated")); assertTrue(note.contains("output-10"))
        assertEquals(2, boundedRecovery(List(5) { "x".repeat(24000) }).size)
        val logs = (1..50).map { AiRequestLog(round = it, request = "x".repeat(240000)) }
        val kept = boundedRequests(logs)
        assertEquals(50, kept.last().round); assertTrue(kept.sumOf { it.request.length } <= 768000)
    }
    @Test fun credentialRedactionCoversEchoesAndBearerFieldsWithoutLeakingThroughToString() {
        val key = "fixture-credential-1234567890"
        val redactor = AiLogRedactor(key)
        assertFalse(redactor.clean("request $key response $key").contains(key))
        assertEquals("Authorization: Bearer [REDACTED]", redactor.clean("Authorization: Bearer another-fixture-token"))
        assertFalse(redactor.toString().contains(key))
        assertEquals("safe source code", redactor.clean("safe source code"))
    }
}
