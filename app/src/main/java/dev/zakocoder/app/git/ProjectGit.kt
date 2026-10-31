package dev.zakocoder.app.git

import android.content.Context
import dev.zakocoder.app.R
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.shellArgument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val GIT_PREFIX = "env GIT_SSL_NO_VERIFY=0 GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt GIT_SSL_CAPATH=/etc/ssl/certs GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TRACE=0 GIT_TRACE_PACKET=0 GIT_TRACE_CURL=0 GIT_CURL_VERBOSE=0 GIT_SSH_COMMAND='ssh -F /dev/null -o BatchMode=yes -o StrictHostKeyChecking=yes -o PermitLocalCommand=no -o ClearAllForwardings=yes' GIT_ALLOW_PROTOCOL=https:ssh git -c credential.helper= -c core.askPass=/bin/false -c credential.useHttpPath=true -c http.followRedirects=false -c core.hooksPath=/dev/null -c protocol.allow=never -c protocol.https.allow=always -c protocol.ssh.allow=always -c core.fsmonitor=false -c diff.external= -c merge.autoEdit=no"
/** Command-line URL-specific TLS and header settings override repository-local HTTP options. */
internal fun gitNetworkPrefix(remote: String): String {
    gitRemoteUrl(remote)
    val options = listOf("url.$remote.insteadOf=$remote", "http.sslVerify=true", "http.sslCAInfo=/etc/ssl/certs/ca-certificates.crt",
        "http.sslCAPath=/etc/ssl/certs", "http.extraHeader=", "http.$remote.sslVerify=true",
        "http.$remote.sslCAInfo=/etc/ssl/certs/ca-certificates.crt", "http.$remote.sslCAPath=/etc/ssl/certs", "http.$remote.extraHeader=")
    return GIT_PREFIX + options.joinToString("") { " -c ${shellArgument(it)}" }
}
fun publicCloneUrl(text: String): String = gitRemoteUrl(text).also { require(it.startsWith("https://")) }
data class GitEntry(val path: String, val index: Char, val working: Char)
data class GitState(
    val projectId: String? = null, val entries: List<GitEntry> = emptyList(), val busy: Boolean = false,
    val repository: Boolean = false, val limited: Boolean = false, val error: String = "", val output: String = "",
    val branch: String = "", val upstream: String = "", val ahead: Int = 0, val behind: Int = 0,
    val detached: Boolean = false, val remotes: List<GitRemote> = emptyList(), val branches: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(), val merging: Boolean = false, val failure: GitFailure? = null, val operation: String = "", val statusKnown: Boolean = false,
)
class ProjectGit(
    private val context: Context,
    private val execute: suspend (String, String) -> CommandResult,
    private val available: () -> Boolean,
    private val networkExecute: suspend (String, String, String) -> CommandResult = { id, script, _ -> execute(id, script) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    fun stop() { job?.cancel() }
    /** Caller must redact credential material before supplying streamed process output. */
    fun reportOutput(text: String) {
        if (mutable.value.busy) mutable.update { it.copy(output = safeGitOutput(text)) }
    }
    private val mutable = MutableStateFlow(GitState()); val state = mutable.asStateFlow()
    fun select(id: String) { if (!mutable.value.busy) { mutable.value = GitState(projectId = id); refresh() } }
    private fun operation(name: String = "refresh", action: suspend (String) -> Unit) {
        val id = mutable.value.projectId ?: return
        if (mutable.value.busy) return
        if (!available()) { fail(GitFailure.Unavailable); return }
        if (name !in setOf("refresh", "init") && !mutable.value.statusKnown) { fail(GitFailure.StatusUnavailable); return }
        mutable.update { it.copy(busy = true, error = "", failure = null, output = "", operation = name) }
        job = scope.launch {
            try { action(id) }
            catch (cancelled: CancellationException) {
                val reason = if (cancelled is TimeoutCancellationException) GitFailure.TimedOut else GitFailure.Cancelled
                withContext(NonCancellable) {
                    try { withTimeout(5000) { status(id) } }
                    catch (_: TimeoutCancellationException) { invalidateStatus(GitFailure.StatusUnavailable) }
                    catch (recheckCancelled: CancellationException) { throw recheckCancelled }
                    catch (_: Exception) { invalidateStatus(GitFailure.StatusUnavailable) }
                    fail(reason)
                }
                throw cancelled
            }
            catch (_: IllegalArgumentException) { fail(GitFailure.InvalidInput) }
            catch (_: Exception) { if (mutable.value.failure == null) fail(GitFailure.Unknown) }
            finally { mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    private fun fail(failure: GitFailure) { mutable.update { it.copy(failure = failure, error = context.getString(if (failure == GitFailure.NotRepository) R.string.git_not_repository else R.string.git_operation_error)) } }
    private suspend fun command(id: String, script: String, remote: String? = null): Boolean {
        // Reject repository URL rewrites before applying the fixed transport policy.
        if (remote != null) {
            try { ensureGitNetworkPolicy { probe -> execute(id, probe) } }
            catch (_: GitUrlRewriteDetected) { fail(GitFailure.UrlRewrite); return false }
            catch (_: GitNetworkPolicyUnavailable) { fail(GitFailure.NetworkPolicy); return false }
        }
        val secured = if (remote == null) script else script.replaceFirst(GIT_PREFIX, gitNetworkPrefix(remote))
        val result = if (remote == null) execute(id, secured) else networkExecute(id, secured, remote)
        mutable.update { it.copy(output = safeGitOutput(if (it.output.endsWith(result.output)) it.output else (it.output + "\n" + result.output).trim())) }
        if (result.exitCode != 0) fail(gitFailure(result.output))
        return result.exitCode == 0
    }
    private fun invalidateStatus(reason: GitFailure) {
        mutable.update { it.copy(repository = false, statusKnown = reason == GitFailure.NotRepository,
            entries = emptyList(), remotes = emptyList(), branches = emptyList(), conflicts = emptyList(),
            branch = "", upstream = "", ahead = 0, behind = 0, detached = false, merging = false, limited = false) }
        fail(reason)
    }
    private suspend fun status(id: String) {
        try {
            val result = execute(id, "$GIT_PREFIX status --porcelain=v2 -z --branch --untracked-files=normal")
            if (result.exitCode != 0) { invalidateStatus(if (gitFailure(result.output) == GitFailure.NotRepository) GitFailure.NotRepository else GitFailure.StatusUnavailable); return }
            // Runtime transcripts retain only their final 100,000 characters. A suffix
            // must never be treated as the complete index when committing changes.
            check(result.output.length < 100000 && result.output.endsWith('\u0000'))
            val parsed = parseGitStatus(result.output)
            check(parsed.detached || parsed.branch.isNotBlank())
            val branchResult = execute(id, "$GIT_PREFIX for-each-ref --format='%(refname:short)' refs/heads/")
            check(branchResult.exitCode == 0 && branchResult.output.length < 100000)
            val branches = branchResult.output.lineSequence().filter { it.isNotEmpty() }.map { gitBranchName(it) }.take(501).toList()
            check(branches.size <= 500)
            val remoteResult = execute(id, "$GIT_PREFIX remote")
            check(remoteResult.exitCode == 0 && remoteResult.output.length < 100000)
            val names = remoteResult.output.lineSequence().filter { it.isNotEmpty() }.map { gitRemoteName(it) }.take(21).toList()
            check(names.size <= 20)
            val remotes = mutableListOf<GitRemote>()
            for (name in names) {
                val url = execute(id, "$GIT_PREFIX remote get-url -- ${shellArgument(name)}")
                check(url.exitCode == 0)
                remotes += GitRemote(name, gitRemoteUrl(url.output.trim()))
            }
            val merge = execute(id, "$GIT_PREFIX rev-parse --verify --quiet MERGE_HEAD")
            check(merge.exitCode in setOf(0, 1))
            mutable.update { it.copy(repository = true, statusKnown = true, entries = parsed.entries.take(500), limited = parsed.entries.size > 500 || parsed.unsafeEntries,
                branch = parsed.branch, upstream = parsed.upstream, ahead = parsed.ahead, behind = parsed.behind, detached = parsed.detached,
                branches = branches, remotes = remotes, conflicts = parsed.entries.filter { entry -> entry.index == 'U' || entry.working == 'U' || "${entry.index}${entry.working}" in setOf("AA", "DD") }.map { it.path }, merging = merge.exitCode == 0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { invalidateStatus(GitFailure.StatusUnavailable) }
    }
    fun refresh() = operation { status(it) }
    fun initialize() = operation("init") { id -> command(id, "$GIT_PREFIX init"); status(id) }
    private fun selected(paths: Set<String>): String {
        val known = mutable.value.entries.map { it.path }.toSet()
        require(paths.isNotEmpty() && paths.size <= 100 && paths.all { it in known && gitSafePath(it) })
        return paths.joinToString(" ", transform = ::shellArgument)
    }
    fun stage(paths: Set<String>) = operation("stage") { id -> command(id, "$GIT_PREFIX --literal-pathspecs add -- " + selected(paths)); status(id) }
    fun resolve(paths: Set<String>) = stage(paths)
    fun markResolved(paths: Set<String>) = resolve(paths)
    fun unstage(paths: Set<String>) = operation("unstage") { id ->
        val files = selected(paths)
        val head = execute(id, "$GIT_PREFIX rev-parse --verify --quiet HEAD")
        command(id, if (head.exitCode == 0) "$GIT_PREFIX --literal-pathspecs restore --staged -- $files" else "$GIT_PREFIX --literal-pathspecs rm --cached -- $files")
        status(id)
    }
    fun commit(message: String, name: String, email: String) = operation("commit") { id ->
        require(mutable.value.repository && !mutable.value.limited && mutable.value.conflicts.isEmpty())
        require(message.isNotBlank() && message.length <= 2000 && '\u0000' !in message && name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl) && email.length <= 200 && email.contains('@') && email.none(Char::isISOControl))
        command(id, "$GIT_PREFIX -c ${shellArgument("user.name=$name")} -c ${shellArgument("user.email=$email")} commit --no-gpg-sign -m ${shellArgument(message)}")
        status(id)
    }
    private fun remote(name: String): GitRemote = mutable.value.remotes.firstOrNull { it.name == gitRemoteName(name) } ?: throw IllegalArgumentException()
    fun fetch(remote: String = "origin") = operation("fetch") { id ->
        val target = remote(remote)
        command(id, "$GIT_PREFIX fetch --progress --no-recurse-submodules -- ${shellArgument(target.url)} ${shellArgument("+refs/heads/*:refs/remotes/${target.name}/*")}", target.url); status(id)
    }
    fun pull(remote: String = "origin", merge: Boolean = false, name: String = "", email: String = "") = operation("pull") { id ->
        if (merge) identity(name, email)
        val target = remote(remote); val branch = gitBranchName(mutable.value.branch)
        require(!mutable.value.detached && mutable.value.conflicts.isEmpty())
        val tracked = mutable.value.destinationBranch(target.name)
        gitBranchName(tracked)
        val fetched = command(id, "$GIT_PREFIX fetch --progress --no-recurse-submodules -- ${shellArgument(target.url)} ${shellArgument("refs/heads/$tracked:refs/remotes/${target.name}/$tracked")}", target.url)
        if (fetched) command(id, "$GIT_PREFIX ${if (merge) identity(name, email) else ""} merge --no-edit --no-gpg-sign --no-stat ${if (merge) "--ff" else "--ff-only"} -- ${shellArgument("refs/remotes/${target.name}/$tracked")}")
        status(id)
    }
    fun push(remote: String = "origin", setUpstream: Boolean = false) = operation("push") { id ->
        val target = remote(remote); val branch = gitBranchName(mutable.value.branch)
        require(!mutable.value.detached && mutable.value.conflicts.isEmpty())
        val tracked = mutable.value.destinationBranch(target.name)
        gitBranchName(tracked)
        val revision = execute(id, "$GIT_PREFIX rev-parse --verify ${shellArgument("refs/heads/$branch")}")
        val oid = revision.output.trim()
        require(revision.exitCode == 0 && oid.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")))
        val pushed = command(id, "$GIT_PREFIX push --progress --no-follow-tags --recurse-submodules=no -- ${shellArgument(target.url)} ${shellArgument("$oid:refs/heads/$tracked")}", target.url)
        if (pushed) command(id, "$GIT_PREFIX update-ref ${shellArgument("refs/remotes/${target.name}/$tracked")} ${shellArgument(oid)}")
        if (pushed && setUpstream) {
            command(id, "$GIT_PREFIX config --local ${shellArgument("branch.$branch.remote")} ${shellArgument(target.name)}")
            command(id, "$GIT_PREFIX config --local ${shellArgument("branch.$branch.merge")} ${shellArgument("refs/heads/$tracked")}")
        }
        status(id)
    }
    fun createBranch(name: String) = operation("createBranch") { id -> command(id, "$GIT_PREFIX switch -c ${shellArgument(gitBranchName(name))}"); status(id) }
    fun switchBranch(name: String) = operation("switchBranch") { id -> require(name in mutable.value.branches); command(id, "$GIT_PREFIX switch --no-guess ${shellArgument(gitBranchName(name))}"); status(id) }
    fun mergeBranch(name: String, authorName: String = "", authorEmail: String = "") = operation("merge") { id -> require(mutable.value.conflicts.isEmpty())
        val ref = if (name in mutable.value.branches) "refs/heads/${gitBranchName(name)}" else {
            require(name == mutable.value.upstream && name.contains('/'))
            val trackingRemote = name.substringBefore('/'); gitRemoteName(trackingRemote)
            require(mutable.value.remotes.any { it.name == trackingRemote })
            "refs/remotes/$trackingRemote/${gitBranchName(name.substringAfter('/'))}"
        }
        command(id, "$GIT_PREFIX ${identity(authorName, authorEmail)} merge --no-edit --no-gpg-sign --no-stat -- ${shellArgument(ref)}"); status(id) }
    private fun identity(name: String, email: String): String {
        require(name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl) && email.length <= 200 && email.contains('@') && email.none(Char::isISOControl))
        return "-c ${shellArgument("user.name=$name")} -c ${shellArgument("user.email=$email")} -c commit.gpgSign=false"
    }
    fun abortMerge() = operation("abortMerge") { id -> require(mutable.value.merging); command(id, "$GIT_PREFIX merge --abort"); status(id) }
}
