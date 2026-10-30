package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.git.*
import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** No guest process, repository, credentials or network: tests partial-operation cancellation state. */
class GitStateRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class Fixture {
        val reachedMerge = CompletableDeferred<Unit>()
        var merging = false
        var breakMetadata = false
        var breakStatus = false
        var suspendMerge = false
        var timeoutNetwork = false
        var networkCalls = 0
        var statusOverride: String? = null
        suspend fun command(script: String): CommandResult = when {
            script.contains("config --get-regexp") -> CommandResult(1, "")
            script.contains("status --porcelain") -> if (breakStatus) CommandResult(128, "fatal: cannot read repository") else CommandResult(0, statusOverride ?: (
                "# branch.head work\u0000# branch.upstream origin/work\u0000# branch.ab +1 -2\u0000" +
                    if (merging) "u UU N... 100644 100644 100644 100644 abc def ghi conflict.kt\u0000" else ""))
            script.contains("for-each-ref") -> if (breakMetadata) CommandResult(1, "cannot read refs") else CommandResult(0, "work\nmain\n")
            script.endsWith(" remote") -> CommandResult(0, "origin\n")
            script.contains("remote get-url") -> CommandResult(0, "https://fixture.invalid/repository.git\n")
            script.contains("MERGE_HEAD") -> CommandResult(if (merging) 0 else 1, "")
            script.contains(" merge ") && suspendMerge -> {
                merging = true; reachedMerge.complete(Unit); awaitCancellation()
            }
            else -> CommandResult(0, "")
        }
        suspend fun network(script: String): CommandResult {
            networkCalls++
            if (timeoutNetwork) withTimeout(5) { delay(1000) }
            return CommandResult(0, "Fetched fixture remote.\n")
        }
    }
    private fun create(fixture: Fixture): ProjectGit = ProjectGit(instrumentation.targetContext,
        { _, script -> fixture.command(script) }, { true }, { _, script, _ -> fixture.network(script) })
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(git: ProjectGit, predicate: (GitState) -> Boolean): GitState = runBlocking { withTimeout(10000) { git.state.first(predicate) } }
    private fun selected(git: ProjectGit) { main { git.select("fixture") }; await(git) { !it.busy && it.statusKnown } }
    @Test fun cancellingAfterFetchRechecksAndShowsActualConflictState() {
        val fixture = Fixture().apply { suspendMerge = true }; val git = create(fixture); selected(git)
        main { git.pull() }
        runBlocking { withTimeout(5000) { fixture.reachedMerge.await() } }
        main { git.stop() }
        val result = await(git) { !it.busy && it.failure == GitFailure.Cancelled }
        assertTrue(result.statusKnown); assertTrue(result.merging); assertEquals(listOf("conflict.kt"), result.conflicts)
        assertTrue(result.output.contains("Fetched fixture remote")); assertEquals(1, fixture.networkCalls)
    }
    @Test fun requestTimeoutIsDistinctFromSuccessfulCompletion() {
        val fixture = Fixture().apply { timeoutNetwork = true }; val git = create(fixture); selected(git)
        main { git.fetch() }
        val result = await(git) { !it.busy && it.failure == GitFailure.TimedOut }
        assertTrue(result.statusKnown); assertFalse(result.merging); assertTrue(result.error.isNotBlank())
    }
    @Test fun failedCancellationRecheckClearsStaleIdentityAndDisablesFurtherMutations() {
        val fixture = Fixture().apply { suspendMerge = true }; val git = create(fixture); selected(git)
        main { git.pull() }; runBlocking { withTimeout(5000) { fixture.reachedMerge.await() } }
        fixture.breakMetadata = true
        main { git.stop() }
        val result = await(git) { !it.busy && it.failure == GitFailure.Cancelled }
        assertFalse(result.statusKnown); assertFalse(result.repository); assertEquals("", result.branch); assertEquals("", result.upstream)
        assertTrue(result.remotes.isEmpty()); assertEquals(0, result.ahead); assertFalse(result.merging)
        main { git.push() }
        assertEquals(GitFailure.StatusUnavailable, git.state.value.failure); assertEquals(1, fixture.networkCalls)
    }
    @Test fun metadataFailureCannotBePresentedAsNoRemotesOrOldBranch() {
        val fixture = Fixture(); val git = create(fixture); selected(git)
        fixture.breakMetadata = true; main { git.refresh() }
        val result = await(git) { !it.busy && it.failure == GitFailure.StatusUnavailable }
        assertFalse(result.statusKnown); assertEquals("", result.branch); assertTrue(result.remotes.isEmpty())
        fixture.breakMetadata = false; main { git.refresh() }
        assertTrue(await(git) { !it.busy && it.statusKnown }.repository)
    }
    @Test fun truncatedStatusNeverEnablesCommitOfUnseenIndexEntries() {
        for (output in listOf("? unseen.kt\u0000", "# branch.head work\u0000? incomplete.kt", "x".repeat(99999) + "\u0000")) {
            val fixture = Fixture(); val git = create(fixture); selected(git)
            fixture.statusOverride = output; main { git.refresh() }
            val result = await(git) { !it.busy && it.failure == GitFailure.StatusUnavailable }
            assertFalse(result.statusKnown); assertTrue(result.entries.isEmpty())
            main { git.commit("must not run", "Fixture", "fixture@example.invalid") }
            assertEquals(GitFailure.StatusUnavailable, git.state.value.failure)
        }
    }
}
