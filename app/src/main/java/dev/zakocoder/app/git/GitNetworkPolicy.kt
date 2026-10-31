package dev.zakocoder.app.git

import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.shellArgument

/** No provider/repository config content is exposed by these exceptions. */
internal class GitUrlRewriteDetected : IllegalArgumentException()
internal class GitNetworkPolicyUnavailable : IllegalStateException()

/**
 * Git's equal-length URL rewrites are ordered, not overridden by ordinary command-line precedence.
 * Reject both fetch and push rewrites before using an approved explicit URL; never mutate config.
 * Invoke immediately before each network operation with the same project and basic GIT_PREFIX.
 */
internal suspend fun ensureGitNetworkPolicy(executeProbe: suspend (String) -> CommandResult) {
    val result = executeProbe("$GIT_PREFIX config --get-regexp ${shellArgument("^url\\..*\\.(insteadof|pushinsteadof)$")}")
    when {
        result.exitCode == 0 -> throw GitUrlRewriteDetected()
        result.exitCode == 1 && result.output.isBlank() -> Unit // Git's documented no-match result.
        else -> throw GitNetworkPolicyUnavailable()
    }
}
