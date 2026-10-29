package dev.zakocoder.app.git

import android.content.Context
import dev.zakocoder.app.R
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.shellArgument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI

internal const val GIT_PREFIX = "env GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -c credential.helper= -c core.hooksPath=/dev/null"
fun publicCloneUrl(text: String): String {
    require(text.length in 1..1000 && text.none(Char::isISOControl))
    val uri = URI(text.trim())
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && !uri.path.isNullOrBlank())
    return uri.toASCIIString()
}
data class GitEntry(val path: String, val index: Char, val working: Char)
data class GitState(val projectId: String? = null, val entries: List<GitEntry> = emptyList(), val busy: Boolean = false, val repository: Boolean = false, val limited: Boolean = false, val error: String = "", val output: String = "")
class ProjectGit(private val context: Context, private val execute: suspend (String, String) -> CommandResult, private val available: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    fun stop() { job?.cancel() }
    private val mutable = MutableStateFlow(GitState()); val state = mutable.asStateFlow()
    fun select(id: String) { if (!mutable.value.busy) { mutable.value = GitState(projectId = id); refresh() } }
    private fun operation(action: suspend (String) -> Unit) {
        val id = mutable.value.projectId ?: return
        if (mutable.value.busy || !available()) return
        mutable.update { it.copy(busy = true, error = "") }
        job = scope.launch {
            try { action(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = context.getString(R.string.git_operation_error)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun status(id: String) {
        val result = execute(id, "$GIT_PREFIX status --porcelain=v1 -z --untracked-files=normal")
        if (result.exitCode != 0) { mutable.update { it.copy(repository = false, entries = emptyList(), error = context.getString(R.string.git_not_repository)) }; return }
        check(result.output.length < 100000) { "Git status output exceeds capture limit" }
        val parts = result.output.split('\u0000'); val entries = mutableListOf<GitEntry>(); var n = 0
        while (n < parts.size) {
            val part = parts[n++]
            if (part.length < 4) continue
            val entry = GitEntry(part.substring(3), part[0], part[1])
            if (entry.path.none(Char::isISOControl)) entries += entry
            if (entry.index in setOf('R', 'C') || entry.working in setOf('R', 'C')) n++
        }
        mutable.update { it.copy(repository = true, entries = entries.take(500), limited = entries.size > 500, output = "") }
    }
    fun refresh() = operation { status(it) }
    fun initialize() = operation { id ->
        check(execute(id, "$GIT_PREFIX init").exitCode == 0); status(id)
    }
    fun stage(paths: Set<String>) = operation { id ->
        val known = mutable.value.entries.map { it.path }.toSet()
        require(paths.isNotEmpty() && paths.size <= 100 && paths.all { it in known && !it.startsWith('/') && it.none(Char::isISOControl) && it.split('/').none { p -> p == ".." } })
        check(execute(id, "$GIT_PREFIX add -- " + paths.joinToString(" ", transform = ::shellArgument)).exitCode == 0)
        status(id)
    }
    fun commit(message: String, name: String, email: String) = operation { id ->
        check(mutable.value.repository && !mutable.value.limited)
        require(message.isNotBlank() && message.length <= 2000 && '\u0000' !in message && name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl) && email.length <= 200 && email.contains('@') && email.none(Char::isISOControl))
        val result = execute(id, "$GIT_PREFIX -c ${shellArgument("user.name=$name")} -c ${shellArgument("user.email=$email")} commit --no-gpg-sign -m ${shellArgument(message)}")
        mutable.update { it.copy(output = result.output.takeLast(12000)) }; check(result.exitCode == 0)
        status(id)
    }
}
