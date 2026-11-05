package dev.zakocoder.app.ai

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentRunnerTest {
    private data class Call(val id: String, val tool: AiTool = AiTool.WriteFile)
    private data class Reply(val calls: List<Call> = emptyList())
    private class Disconnected : Exception()
    private class AwaitAnswer : Exception()

    private suspend fun run(
        request: suspend () -> Reply,
        accept: suspend (Reply) -> Unit = {},
        complete: suspend (Reply) -> Boolean = { true },
        prepare: suspend (Reply, List<Call>) -> List<String> = { _, calls -> calls.map { it.id } },
        execute: suspend (Call) -> String,
        commit: (Call, String) -> Unit = { _, _ -> },
        finish: suspend () -> Unit = {},
    ) = AgentRunner().run(request, accept, { it.calls }, complete, prepare,
        { _, call, _ -> execute(call) }, { _, call, _, result -> commit(call, result) }, finish)

    @Test fun successfulFileWriteIsNotReplayedWhenFollowingRequestFailsAndSessionResumes() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("agent-runner-").toFile()
        try {
            val file = File(root, "result.txt")
            val transcript = mutableListOf<String>()
            var request = 0
            var writes = 0
            suspend fun session() = run(
                request = {
                    when (++request) {
                        1 -> Reply(listOf(Call("write-1")))
                        2 -> { assertEquals(listOf("write-1:written"), transcript); throw Disconnected() }
                        else -> { assertEquals("new content", file.readText()); Reply() }
                    }
                },
                execute = { writes++; file.writeText("new content"); "written" },
                commit = { call, result -> transcript += "${call.id}:$result" },
            )
            try { session(); fail("request should fail") } catch (_: Disconnected) { }
            assertEquals(1, writes)
            assertEquals("new content", file.readText())
            session() // The owner resumes from retained results, rather than replaying the batch.
            assertEquals(1, writes)
            assertEquals(listOf("write-1:written"), transcript)
        } finally { root.deleteRecursively() }
    }

    @Test fun transportRetryIsConfinedToRequestAndCannotReplayCompletedTools() = runBlocking {
        var rounds = 0
        var attempts = 0
        var executed = 0
        var committed = 0
        run(request = {
            if (++rounds == 1) Reply(listOf(Call("a"))) else {
                repeat(2) { attempts++; assertEquals(1, executed); assertEquals(1, committed) }
                Reply()
            }
        }, execute = { executed++; "ok" }, commit = { _, _ -> committed++ })
        assertEquals(2, attempts)
        assertEquals(1, executed)
    }

    @Test fun everyToolStillPassesSessionPermissionGateIncludingAlternativeTool() = runBlocking {
        val policy = AiPolicy(overrides = mapOf(AiTool.WriteFile to AiPermission.Deny, AiTool.RunCommand to AiPermission.Deny))
        var rounds = 0
        var writes = 0
        val checked = mutableListOf<AiTool>()
        val results = mutableListOf<String>()
        run(request = {
            when (++rounds) {
                1 -> Reply(listOf(Call("write")))
                2 -> Reply(listOf(Call("shell", AiTool.RunCommand)))
                else -> Reply()
            }
        }, execute = { call ->
            checked += call.tool
            if (policy.permission(call.tool) == AiPermission.Deny) "denied" else { writes++; "written" }
        }, commit = { _, result -> results += result })
        assertEquals(listOf(AiTool.WriteFile, AiTool.RunCommand), checked)
        assertEquals(listOf("denied", "denied"), results)
        assertEquals(0, writes)
    }

    @Test fun cancellationAfterSideEffectCommitsItsResultButNeverStartsNextToolOrRequest() = runBlocking {
        val results = mutableListOf<String>()
        var requests = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            run(request = { requests++; Reply(listOf(Call("first"), Call("second"))) },
                execute = { call -> currentCoroutineContext().cancel(); "${call.id}:written" },
                commit = { _, result -> results += result })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("first:written"), results)
        assertEquals(1, requests)
    }

    @Test fun nextRequestAndCompressionSeeOnlyFullyPairedCompletedBatches() = runBlocking {
        val messages = mutableListOf<String>()
        var round = 0
        run(request = {
            if (++round == 1) Reply(listOf(Call("a"), Call("b"))) else {
                assertEquals(listOf("call:a", "call:b", "result:a", "result:b", "images"), messages)
                // A session performs compaction here, after all results, not between calls.
                messages += "compacted"
                Reply()
            }
        }, accept = { reply -> messages += reply.calls.map { "call:${it.id}" } },
            execute = { "ok" }, commit = { call, _ -> messages += "result:${call.id}" },
            finish = { messages += "images" })
        assertEquals("compacted", messages.last())
    }

    @Test fun questionPausePropagatesWithoutResultOrNextRequest() = runBlocking {
        var requests = 0
        var committed = 0
        try {
            run(request = { requests++; Reply(listOf(Call("question", AiTool.AskUser))) },
                execute = { throw AwaitAnswer() }, commit = { _, _ -> committed++ })
            fail("must suspend to owner")
        } catch (_: AwaitAnswer) { }
        assertEquals(1, requests)
        assertEquals(0, committed)
    }

    @Test fun completionMayContinueAfterUserSteeringAtSafeBoundary() = runBlocking {
        var requests = 0
        var completions = 0
        run(request = { requests++; Reply() }, complete = { ++completions == 2 }, execute = { error("no tools") })
        assertEquals(2, requests)
        assertEquals(2, completions)
    }

    @Test fun malformedEventMappingFailsBeforeAnySideEffect() = runBlocking {
        var executed = false
        try {
            run(request = { Reply(listOf(Call("a"), Call("b"))) }, prepare = { _, _ -> listOf("a") },
                execute = { executed = true; "ok" })
            fail("missing event")
        } catch (_: IllegalStateException) { }
        assertFalse(executed)
    }

    @Test fun lateNonCooperativeTransportCannotAcceptOrExecuteAfterCancellation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var accepted = false
        var executed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            run(request = { withContext(NonCancellable) { gate.await(); Reply(listOf(Call("late"))) } },
                accept = { accepted = true }, execute = { executed = true; "ok" })
        }
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertFalse(accepted)
        assertFalse(executed)
        assertTrue(job.isCancelled)
    }
}
