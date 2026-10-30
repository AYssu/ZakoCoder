package dev.zakocoder.app.ai

import android.content.Context
import dev.zakocoder.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

private class AiProbeUnsupported : Exception()

enum class AiProbe { Idle, Models, Testing, Passed, Failed, Cancelled }
data class AiModelsState(val catalog: AiCatalog = AiCatalog(), val loading: Boolean = true, val saving: Boolean = false,
    val revision: Int = 0, val error: String = "", val probe: AiProbe = AiProbe.Idle, val probeMessage: String = "", val available: List<String> = emptyList(),
    val probeProgress: AiModelProbeProgress? = null) {
    val probing get() = probe == AiProbe.Models || probe == AiProbe.Testing
}
/** Owns configuration IO/network. UI only holds unsaved fields and replacement credentials. */
class AiModelsController(private val context: Context, private val store: AiConfigStore, private val transport: ChatTransport,
    private val taskActive: () -> Boolean, private val changed: (AiProfile?) -> Unit,
    /** Same settings proxy as chat requests, so model discovery/testing honours it too. */
    private val proxy: () -> String = { "" }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AiModelsState())
    val state = mutable.asStateFlow()
    private var probe: Job? = null
    @Volatile private var generation = 0L
    init { scope.launch { reload() } }
    private suspend fun reload(): Boolean {
        try {
            val catalog = withContext(Dispatchers.IO) { store.catalog() }
            mutable.update { it.copy(catalog = catalog, loading = false, error = "") }; changed(catalog.active)
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(loading = false, error = context.getString(R.string.ai_models_load_error)) }; changed(null); return false }
    }
    fun retry() { if (!mutable.value.loading && !mutable.value.saving) { mutable.update { it.copy(loading = true) }; scope.launch { reload() } } }
    fun cancelProbe() { generation++; probe?.cancel(); mutable.update { it.copy(probe = AiProbe.Idle, probeMessage = "", available = emptyList(), probeProgress = null) } }
    /** Explicit stop keeps the already received, redacted test output visible. Editing resets it. */
    fun stopProbe() {
        val wasTesting = mutable.value.probe == AiProbe.Testing
        generation++; probe?.cancel()
        mutable.update { it.copy(probe = if (wasTesting) AiProbe.Cancelled else AiProbe.Idle,
            probeMessage = if (wasTesting) context.getString(R.string.ai_probe_cancelled) else "",
            probeProgress = it.probeProgress?.let { progress -> progress.copy(elapsedMs = (System.currentTimeMillis() - progress.startedAt).coerceAtLeast(progress.elapsedMs)) }) }
    }
    private fun mutation(block: () -> Unit) {
        if (taskActive() || mutable.value.loading || mutable.value.saving || mutable.value.error.isNotEmpty()) return
        cancelProbe(); mutable.update { it.copy(saving = true, error = "") }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                if (reload()) mutable.update { it.copy(revision = it.revision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = message(error)) } }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun save(draft: AiModelDraft) = mutation { store.saveProfile(draft) }
    fun activate(id: String) = mutation { store.activate(id) }
    fun delete(id: String) = mutation { store.delete(id) }
    fun clearError() { if (!mutable.value.loading && !mutable.value.saving) mutable.update { it.copy(error = "") } }
    fun discover(draft: AiModelDraft) = probe(draft, false)
    fun test(draft: AiModelDraft) = probe(draft, true)
    private fun probe(draft: AiModelDraft, testing: Boolean) {
        if (taskActive() || mutable.value.loading || mutable.value.saving) return
        cancelProbe(); val owner = generation
        mutable.update { it.copy(probe = if (testing) AiProbe.Testing else AiProbe.Models,
            probeProgress = if (testing) AiModelProbeProgress() else null) }
        probe = scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { store.resolve(draft, requireModel = testing) }
                if (!testing) {
                    val models = transport.models(config, proxy())
                    if (owner == generation) mutable.update { it.copy(probe = AiProbe.Idle, available = models, probeMessage = context.getString(if (models.isEmpty()) R.string.ai_models_remote_empty else R.string.ai_models_fetched, models.size)) }
                } else {
                    withTimeout(30000) {
                        val parameters = JSONObject().put("type", "object").put("properties", JSONObject().put("ack", JSONObject().put("type", "string").put("enum", JSONArray(listOf("ok")))))
                            .put("required", JSONArray(listOf("ack"))).put("additionalProperties", false)
                        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "connection_probe").put("description", "Acknowledge a connection test; no action is performed").put("parameters", parameters)))
                        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Connection test only. Call connection_probe with ack=ok. No project files or commands are involved."))
                        val reply = transport.request(config, messages, tools, forceTool = "connection_probe", proxy = proxy(), observe = { log ->
                            mutable.update { state ->
                                if (owner == generation) state.copy(probeProgress = AiModelProbeProgress.from(log)) else state
                            }
                        }) {}
                        if (!runCatching { reply.calls.size == 1 && reply.calls[0].name == "connection_probe" && JSONObject(reply.calls[0].arguments).optString("ack") == "ok" }.getOrDefault(false)) throw AiProbeUnsupported()
                    }
                    if (owner == generation) mutable.update { it.copy(probe = AiProbe.Passed, probeMessage = context.getString(R.string.ai_models_test_passed)) }
                }
            } catch (_: TimeoutCancellationException) { if (owner == generation) mutable.update { it.copy(probe = AiProbe.Failed, probeMessage = context.getString(R.string.ai_models_test_timeout)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (owner == generation) mutable.update { it.copy(probe = AiProbe.Failed, probeMessage = message(error)) } }
        }
    }
    private fun message(error: Exception): String = context.getString(when {
        error is AiProbeUnsupported -> R.string.ai_models_no_tools
        error is AiOriginChanged -> R.string.ai_models_origin_changed
        error is AiTransportException -> when (error.status) {
            401, 403 -> R.string.ai_models_auth_error
            404 -> R.string.ai_models_not_found
            429 -> R.string.ai_models_rate_limit
            400, 422 -> R.string.ai_models_request_rejected
            null -> R.string.ai_models_network_error
            else -> R.string.ai_models_service_error
        }
        error is IllegalArgumentException -> R.string.ai_models_invalid
        else -> R.string.ai_models_save_error
    })
}
