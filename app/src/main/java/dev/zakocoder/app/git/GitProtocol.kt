package dev.zakocoder.app.git

import java.net.URI

/** Commands use explicit transports and never accept Git remote-helper syntax. */
fun gitRemoteUrl(text: String): String {
    require(text.length in 1..1000 && text.none(Char::isISOControl))
    val value = text.trim()
    val uri = URI(value)
    require(uri.scheme in setOf("https", "ssh") && !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null && !uri.path.isNullOrBlank())
    if (uri.scheme == "https") require(uri.userInfo == null)
    else require(uri.userInfo == null || uri.userInfo.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
    require(uri.port == -1 || uri.port in 1..65535)
    return uri.toASCIIString()
}
internal fun gitBranchName(name: String): String {
    require(name.length in 1..200 && !name.startsWith('-') && !name.startsWith('/') && !name.endsWith('/') && !name.endsWith('.') && !name.contains("..") && !name.contains("@{") && name != "@")
    require(name.none { it.isISOControl() || it.isWhitespace() || it in "~^:?*[\\" })
    require(name.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") })
    return name
}
internal fun gitRemoteName(name: String): String {
    require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) && !name.contains(".."))
    return name
}
internal fun gitSafePath(path: String): Boolean = path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') && path.none(Char::isISOControl) && path.split('/').none { it in setOf("..", ".git") }

data class GitRemote(val name: String, val url: String)
enum class GitFailure { NotRepository, Authentication, Network, Conflict, NonFastForward, DirtyWorktree, MissingUpstream, InvalidInput, Rejected, Unavailable, Unknown, Cancelled, TimedOut, StatusUnavailable, UrlRewrite, NetworkPolicy }
internal data class GitStatusSnapshot(val entries: List<GitEntry>, val branch: String, val upstream: String, val ahead: Int, val behind: Int, val detached: Boolean, val unsafeEntries: Boolean = false)
internal fun parseGitStatus(output: String): GitStatusSnapshot {
    require(output.length < 1000000)
    var branch = ""; var upstream = ""; var ahead = 0; var behind = 0; var detached = false
    var unsafeEntries = false
    val entries = mutableListOf<GitEntry>(); val parts = output.split('\u0000'); var cursor = 0
    while (cursor < parts.size) {
        val part = parts[cursor++]
        when {
            part.startsWith("# branch.head ") -> { branch = part.removePrefix("# branch.head "); detached = branch == "(detached)"; if (detached) branch = "" }
            part.startsWith("# branch.upstream ") -> upstream = part.removePrefix("# branch.upstream ")
            part.startsWith("# branch.ab ") -> { val counts = part.removePrefix("# branch.ab ").split(' '); ahead = counts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0; behind = counts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0 }
            part.startsWith("1 ") || part.startsWith("2 ") || part.startsWith("u ") -> {
                val fields = part.split(' ', limit = when (part[0]) { '1' -> 9; '2' -> 10; else -> 11 })
                val expected = when (part[0]) { '1' -> 9; '2' -> 10; else -> 11 }
                require(fields.size == expected && fields[1].length == 2)
                val path = fields.last(); if (gitSafePath(path)) entries += GitEntry(path, fields[1][0].let { if (it == '.') ' ' else it }, fields[1][1].let { if (it == '.') ' ' else it }) else unsafeEntries = true
                if (part[0] == '2') { require(cursor < parts.size); cursor++ }
            }
            part.startsWith("? ") -> { val path = part.substring(2); if (gitSafePath(path)) entries += GitEntry(path, '?', '?') else unsafeEntries = true }
        }
    }
    return GitStatusSnapshot(entries, branch, upstream, ahead.coerceAtLeast(0), behind.coerceAtLeast(0), detached, unsafeEntries)
}
internal fun gitFailure(output: String): GitFailure {
    val value = output.lowercase()
    return when {
        "git: not found" in value || "git: command not found" in value -> GitFailure.Unavailable
        "not a git repository" in value -> GitFailure.NotRepository
        listOf("authentication failed", "could not read username", "permission denied (publickey)", "terminal prompts disabled", "host key verification failed").any { it in value } -> GitFailure.Authentication
        listOf("could not resolve", "unable to access", "connection refused", "connection timed out", "network is unreachable").any { it in value } -> GitFailure.Network
        "conflict" in value || "unmerged" in value -> GitFailure.Conflict
        "non-fast-forward" in value || "not possible to fast-forward" in value || "divergent branches" in value -> GitFailure.NonFastForward
        "would be overwritten" in value || "unstaged changes" in value || "local changes" in value -> GitFailure.DirtyWorktree
        "no upstream" in value || "no tracking information" in value -> GitFailure.MissingUpstream
        "rejected" in value || "remote rejected" in value -> GitFailure.Rejected
        else -> GitFailure.Unknown
    }
}
internal fun safeGitOutput(output: String): String = output.takeLast(24000)
    .replace(Regex("(?i)(https?://)[^\\s/@]+(?::[^\\s/@]*)?@"), "$1[redacted]@")
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*)([^\\r\\n]+)"), "$1[redacted]")

internal fun GitState.tracksRemote(remote: String): Boolean = remote.isNotBlank() && upstream.startsWith("$remote/")
internal fun GitState.destinationBranch(remote: String): String = if (tracksRemote(remote)) upstream.removePrefix("$remote/") else branch
internal fun GitState.preferredRemote(current: String): String = remotes.firstOrNull { it.name == current }?.name
    ?: remotes.firstOrNull { tracksRemote(it.name) }?.name ?: remotes.firstOrNull { it.name == "origin" }?.name ?: remotes.firstOrNull()?.name.orEmpty()
