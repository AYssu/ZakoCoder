package dev.zakocoder.app.memory

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MemoryState(
    val entries: List<AgentMemory> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: MemoryFailure? = null,
    val loaded: Boolean = false,
    val visible: List<AgentMemory> = emptyList(),
    val searching: Boolean = false,
) {
    val proposals: List<AgentMemory> get() = entries.filter { it.pending }
}

internal class MemoryController(private val repository: MemoryRepository, private val scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(MemoryRepository(context), scope)
    private val mutable = MutableStateFlow(MemoryState())
    private val operations = Mutex()
    private val searchGeneration = AtomicLong()
    private var searchJob: Job? = null
    private val searchLock = Any()
    val state: StateFlow<MemoryState> = mutable.asStateFlow()

    fun load(): Job = scope.launch { operations.withLock {
        mutable.update { it.copy(loading = true, error = null) }
        try {
            val entries = repository.load()
            mutable.update { it.copy(entries = entries, loaded = true) }
            refreshSearch(debounce = false)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: MemoryStorageException) { mutable.update { it.copy(error = failure.failure, loaded = false) } }
        finally { mutable.update { it.copy(loading = false) } }
    } }

    fun search(query: String) {
        mutable.update { it.copy(query = query.take(MemoryPolicy.MAX_QUERY_CHARS)) }
        refreshSearch(debounce = true)
    }
    fun clearError() { mutable.update { if (it.loaded) it.copy(error = null) else it } }

    fun add(title: String, content: String, core: Boolean = false, source: String = "user"): Job = change { entries ->
        val now = System.currentTimeMillis()
        entries + AgentMemory(UUID.randomUUID().toString(), title.trim(), content.trim(), source.trim(), now, now, core = core)
    }

    /** Suggestions are persisted separately and never returned in context until accepted by the user. */
    fun propose(title: String, content: String, source: String): Job = change { entries ->
        val existing = entries.any { it.title == title.trim() && it.content == content.trim() }
        if (existing) entries else {
            val now = System.currentTimeMillis()
            entries + AgentMemory(UUID.randomUUID().toString(), title.trim(), content.trim(), source.trim(), now, now,
                enabled = false, pending = true)
        }
    }

    fun update(id: String, title: String, content: String, core: Boolean): Job = change { entries ->
        entries.map { if (it.id == id) it.copy(title = title.trim(), content = content.trim(), core = core,
            updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt)) else it }
    }
    fun remove(id: String): Job = change { entries -> entries.filterNot { it.id == id } }
    fun dismissProposal(id: String): Job = remove(id)
    fun acceptProposal(id: String): Job = change { entries -> entries.map {
        if (it.id == id && it.pending) it.copy(enabled = true, pending = false, updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt)) else it
    } }
    fun setEnabled(id: String, enabled: Boolean): Job = change { entries -> entries.map {
        if (it.id == id && !it.pending) it.copy(enabled = enabled, updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt)) else it
    } }

    suspend fun contextFor(query: String): MemoryContextSnapshot {
        val entries = repository.load()
        return withContext(Dispatchers.Default) { MemoryPolicy.context(entries, query) }
    }
    suspend fun searchFor(query: String, limit: Int = 8): List<AgentMemory> {
        val entries = repository.load()
        return withContext(Dispatchers.Default) {
            MemoryPolicy.search(entries.filter { it.enabled && !it.pending }, query).take(limit.coerceIn(0, 16))
        }
    }

    private fun refreshSearch(debounce: Boolean) {
        synchronized(searchLock) {
            val generation = searchGeneration.incrementAndGet()
            searchJob?.cancel()
            mutable.update { it.copy(searching = true) }
            searchJob = scope.launch {
                if (debounce) delay(120)
                val snapshot = mutable.value
                val visible = withContext(Dispatchers.Default) {
                    MemoryPolicy.search(snapshot.entries.filterNot { it.pending }, snapshot.query)
                }
                mutable.update { current ->
                    if (generation != searchGeneration.get() || current.query != snapshot.query || current.entries !== snapshot.entries) current
                    else current.copy(visible = visible, searching = false)
                }
            }
        }
    }

    private fun change(block: (List<AgentMemory>) -> List<AgentMemory>): Job = scope.launch { operations.withLock {
        mutable.update { it.copy(busy = true, error = null) }
        try {
            val entries = repository.change(block)
            mutable.update { it.copy(entries = entries, loaded = true, loading = false) }
            refreshSearch(debounce = false)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: MemoryStorageException) { mutable.update { it.copy(error = failure.failure,
            loaded = it.loaded && failure.failure !in setOf(MemoryFailure.Corrupt, MemoryFailure.UnsupportedVersion)) } }
        finally { mutable.update { it.copy(busy = false, loading = false) } }
    } }
}
