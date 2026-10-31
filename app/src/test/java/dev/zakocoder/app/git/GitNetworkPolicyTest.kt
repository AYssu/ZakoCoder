package dev.zakocoder.app.git

import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GitNetworkPolicyTest {
    @Test fun noMatchingRulesAllowsWithoutChangingRepositoryConfiguration() = runBlocking {
        var calls = 0
        ensureGitNetworkPolicy { probe ->
            calls++
            assertTrue(probe.startsWith(GIT_PREFIX))
            assertTrue(probe.contains("config --get-regexp"))
            assertTrue(probe.contains("insteadof|pushinsteadof"))
            assertFalse(probe.contains("--unset")); assertFalse(probe.contains("--replace-all"))
            assertFalse(probe.contains(" fetch ")); assertFalse(probe.contains(" push "))
            CommandResult(1, "")
        }
        assertEquals(1, calls)
    }
    @Test fun fetchPushAndEmptyWildcardRulesAllFailClosedWithoutExposingTheirValues() = runBlocking {
        for (row in listOf("url.https://other.invalid/repo.insteadof https://approved.invalid/repo\n",
            "url.https://other.invalid/repo.pushinsteadof https://approved.invalid/repo\n",
            "url.https://other.invalid/.insteadof \n", "")) {
            try { ensureGitNetworkPolicy { CommandResult(0, row) }; fail("Rule match must block") }
            catch (failure: GitUrlRewriteDetected) { assertNull(failure.message) }
        }
    }
    @Test fun malformedMissingAndTruncatedProbeCannotPretendThatRepositoryHasNoRewriteRules() = runBlocking {
        for (result in listOf(CommandResult(128, "config parse error"), CommandResult(127, "git unavailable"),
            CommandResult(1, "unexpected nonempty output"), CommandResult(2, ""))) {
            try { ensureGitNetworkPolicy { result }; fail("Unverified config must block network") }
            catch (failure: GitNetworkPolicyUnavailable) { assertNull(failure.message) }
        }
    }
    @Test fun cancellationAlwaysPropagates() = runBlocking {
        try { ensureGitNetworkPolicy { throw CancellationException("fixture cancellation") }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }
}
