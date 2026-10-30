package dev.zakocoder.app.mcp

import android.content.Context
import dev.zakocoder.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Configuration does not grant tool approval. Callers must approve every external operation. */
class McpController(
    private val context: Context, private val scope: CoroutineScope,
    private val startProcess: suspend (projectId: String, server: McpServer) -> Process,
    private val canModify: () -> Boolean = { true },
    keyAlias: String = "zakocoder-mcp-v1",
) {
    private val mutable = MutableStateFlow(McpState())
    val state = mutable.asStateFlow()
    private val store = McpStore(context, keyAlias)
    private val configuration = Mutex()
    private val execution = Mutex()
    private val monitor = Any()
    private var revision = 0L
    @Volatile private var loadSucceeded = false
    private var operations = 0
    private var externalOperations = 0
    private var firstLoad = true
    private val epochs = mutableMapOf<String, Long>()
    private val sessions = mutableMapOf<Pair<String, String>, McpSession>()
    private val actions = setOf("tools/list", "tools/call", "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get")
    init { load() }

    fun load(): Job = operation {
        configuration.withLock {
            if (!firstLoad && !canModify()) throw McpException(McpProblem.Unavailable)
            firstLoad = false
            val servers = try { withContext(Dispatchers.IO) { store.load() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    loadSucceeded = false; invalidateAll()
                    mutable.update { it.copy(servers = emptyList()) }
                    throw McpException(McpProblem.Storage)
                }
            invalidateAll()
            loadSucceeded = true
            mutable.update { it.copy(servers = servers, loading = false, error = "", probeResult = "", probeServerId = "", probeProjectId = "") }
        }
    }
    /** Imports atomically; a conflicting existing name rejects the entire import. */
    fun importJson(source: String, singleName: String = ""): Job = change {
        val imported = withContext(Dispatchers.IO) { McpConfigParser.parse(source, singleName) }
        val existing = mutable.value.servers
        if (existing.any { old -> imported.any { it.name == old.name || it.id == old.id } }) throw McpException(McpProblem.InvalidConfig)
        existing + imported
    }
    fun save(server: McpServer): Job = change {
        McpConfigParser.validate(server)
        val existing = mutable.value.servers
        if (existing.any { it.id != server.id && it.name == server.name }) throw McpException(McpProblem.InvalidConfig)
        if (existing.any { it.id == server.id }) existing.map { if (it.id == server.id) snapshot(server) else it }
        else existing + snapshot(server)
    }
    fun setEnabled(id: String, enabled: Boolean): Job = change {
        if (mutable.value.servers.none { it.id == id }) throw McpException(McpProblem.InvalidConfig)
        mutable.value.servers.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }
    fun remove(id: String): Job = change {
        if (mutable.value.servers.none { it.id == id }) throw McpException(McpProblem.InvalidConfig)
        mutable.value.servers.filterNot { it.id == id }
    }
    private fun change(next: suspend () -> List<McpServer>): Job = operation {
        configuration.withLock {
            if (!loadSucceeded) throw McpException(McpProblem.Storage)
            if (!canModify()) throw McpException(McpProblem.Unavailable)
            val result = next()
            if (!canModify()) throw McpException(McpProblem.Unavailable)
            if (result.size > 32) throw McpException(McpProblem.Limit)
            try { withContext(Dispatchers.IO) { store.save(result) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw McpException(McpProblem.Storage) }
            invalidateAll()
            mutable.update { it.copy(servers = result, probeResult = "", probeServerId = "", probeProjectId = "", error = "") }
        }
    }
    fun validateRequest(id: String, method: String, params: JSONObject) {
        if (mutable.value.loading || !loadSucceeded) throw McpException(McpProblem.Storage)
        val server = mutable.value.servers.firstOrNull { it.id == id } ?: throw McpException(McpProblem.InvalidConfig)
        if (!server.enabled) throw McpException(McpProblem.Disabled)
        McpConfigParser.validate(server)
        if (method !in actions) throw McpException(McpProblem.Protocol)
        if (params.toString().toByteArray().size > 256 * 1024) throw McpException(McpProblem.Limit)
        val allowed = when (method) {
            "tools/call" -> setOf("name", "arguments")
            "resources/read" -> setOf("uri")
            "prompts/get" -> setOf("name", "arguments")
            else -> setOf("cursor")
        }
        if (!params.keys().asSequence().all { it in allowed }) throw McpException(McpProblem.Protocol)
        if (method in setOf("tools/call", "prompts/get")) {
            if (params.opt("name") !is String || params.getString("name").isBlank() || params.getString("name").length > 512) throw McpException(McpProblem.Protocol)
            if (params.has("arguments") && params.opt("arguments") !is JSONObject) throw McpException(McpProblem.Protocol)
            if (method == "prompts/get" && params.optJSONObject("arguments")?.let { args -> args.keys().asSequence().any { args.get(it) !is String } } == true) throw McpException(McpProblem.Protocol)
        }
        if (method == "resources/read" && (params.opt("uri") !is String || params.getString("uri").length !in 1..4096)) throw McpException(McpProblem.Protocol)
        if (params.has("cursor") && (params.opt("cursor") !is String || params.getString("cursor").length > 8192)) throw McpException(McpProblem.Protocol)
    }
    fun preview(id: String, method: String, params: JSONObject): String {
        validateRequest(id, method, params)
        val server = mutable.value.servers.single { it.id == id }
        val destination = if (server.transport == McpTransport.Stdio) "${server.command}\n${context.getString(R.string.mcp_guest_process)}" else server.url
        return redact("${server.transport}\n$destination\n$method\n${params.toString(2)}").take(24000)
    }
    /** Exact configured secret values are removed from display/output; no logging occurs here. */
    fun redact(text: String): String {
        val values = mutable.value.servers.flatMap { server ->
            server.args + server.env.values + server.headers.values + server.headers.filterKeys { it.equals("Authorization", true) || it.equals("Proxy-Authorization", true) }
                .values.mapNotNull { it.substringAfter(' ', "").takeIf(String::isNotBlank) }
        }.filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
        if (values.isEmpty()) return text
        // A single pass avoids matching secrets again inside an inserted redaction marker.
        val pattern = Regex(values.joinToString("|") { Regex.escape(it) })
        return pattern.replace(text) { "[redacted]" }.also { if (it.length > 2 * 1024 * 1024) throw McpException(McpProblem.Limit) }
    }
    fun probe(projectId: String, id: String): Job = operation(external = true) {
        val owner = epoch(projectId)
        val expected = synchronized(monitor) { revision }
        execution.withLock {
            if (!canModify()) throw McpException(McpProblem.Unavailable)
            checkIdentity(projectId, owner, expected)
            val server = readyServer(id)
            val session = session(projectId, server, owner, expected)
            try {
                val result = session.probe()
                val capabilities = result.getJSONObject("capabilities")
                val summary = JSONObject()
                listOf("tools", "resources", "prompts").forEach { if (capabilities.has(it)) summary.put(it, true) }
                result.put("capabilities", summary)
                val tools = if (capabilities.has("tools")) list(session, "tools/list", JSONObject()) else JSONObject().put("tools", JSONArray())
                val discovered = tools.getJSONArray("tools"); val shown = JSONArray(); var bytes = 0
                for (index in 0 until discovered.length()) {
                    val item = redactJson(discovered.getJSONObject(index)) as JSONObject
                    val count = item.toString().toByteArray().size
                    if (bytes + count > 240000) { result.put("truncated", true); break }
                    shown.put(item); bytes += count
                }
                result.put("tools", shown).put("untrusted", true)
                if (tools.optBoolean("truncated")) result.put("truncated", true)
                checkIdentity(projectId, owner, expected)
                mutable.update { it.copy(probeServerId = id, probeProjectId = projectId, probeResult = (redactJson(result) as JSONObject).toString(), error = "") }
            } finally { discard(projectId, id, session) }
        }
    }
    suspend fun request(projectId: String, id: String, action: String, arguments: JSONObject): JSONObject {
        // Snapshot before queueing: callers cannot mutate a pending approved JSON object.
        val params = JSONObject(arguments.toString())
        validateRequest(id, action, params)
        val owner = epoch(projectId)
        val expected = synchronized(monitor) { revision }
        begin(external = true)
        try {
            return execution.withLock {
                checkIdentity(projectId, owner, expected)
                validateRequest(id, action, params)
                val session = session(projectId, readyServer(id), owner, expected)
                try {
                    val result = if (action.endsWith("/list")) list(session, action, params) else session.exchange(action, params)
                    checkIdentity(projectId, owner, expected)
                    // Preserve structured result flags (including tools/call isError).
                    redactJson(result) as JSONObject
                } catch (failure: Exception) { discard(projectId, id, session); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: McpException) { throw failure }
        catch (_: Exception) { throw McpException(McpProblem.Unavailable) }
        finally { end(external = true) }
    }
    private suspend fun list(session: McpSession, method: String, initial: JSONObject): JSONObject {
        val key = when (method) { "tools/list" -> "tools"; "resources/list" -> "resources"; "resources/templates/list" -> "resourceTemplates"; "prompts/list" -> "prompts"; else -> throw McpException(McpProblem.Protocol) }
        val items = JSONArray(); val cursors = mutableSetOf<String>(); var params = initial; var pages = 0; var bytes = 0
        initial.optString("cursor").takeIf { it.isNotEmpty() }?.let(cursors::add)
        while (true) {
            val page = session.exchange(method, params); pages++
            val values = page.optJSONArray(key) ?: throw McpException(McpProblem.Protocol)
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: throw McpException(McpProblem.Protocol)
                bytes += item.toString().toByteArray().size
                if (bytes > 1024 * 1024 || items.length() >= 1000) throw McpException(McpProblem.Limit)
                items.put(item)
            }
            if (!page.has("nextCursor")) return JSONObject().put(key, items)
            val next = page.opt("nextCursor") as? String ?: throw McpException(McpProblem.Protocol)
            if (next.length !in 1..8192 || !cursors.add(next)) throw McpException(McpProblem.Protocol)
            if (pages >= 20) return JSONObject().put(key, items).put("nextCursor", next).put("truncated", true)
            params = JSONObject().put("cursor", next)
        }
    }
    private fun readyServer(id: String): McpServer {
        if (!loadSucceeded || mutable.value.loading) throw McpException(McpProblem.Storage)
        return mutable.value.servers.firstOrNull { it.id == id }?.also { if (!it.enabled) throw McpException(McpProblem.Disabled) }
            ?: throw McpException(McpProblem.InvalidConfig)
    }
    private fun session(project: String, server: McpServer, owner: Long, expected: Long): McpSession = synchronized(monitor) {
        checkIdentity(project, owner, expected)
        sessions.getOrPut(project to server.id) { McpSession(server, scope, { startProcess(project, server) }) }
    }
    private suspend fun discard(project: String, id: String, session: McpSession) {
        synchronized(monitor) { if (sessions[project to id] === session) sessions.remove(project to id) }
        session.close()
    }
    suspend fun closeTask(projectId: String) {
        val owned = synchronized(monitor) {
            epochs[projectId] = (epochs[projectId] ?: 0) + 1
            val selected = sessions.filterKeys { it.first == projectId }.values.toList()
            sessions.keys.removeAll { it.first == projectId }
            selected
        }
        owned.forEach { it.close() }
    }
    fun closeProject(projectId: String): Job = scope.launch { closeTask(projectId) }
    fun closeAll(): Job = scope.launch { invalidateAll() }
    private suspend fun invalidateAll() {
        val old = synchronized(monitor) { revision++; val values = sessions.values.toList(); sessions.clear(); values }
        old.forEach { it.close() }
    }
    private fun epoch(project: String): Long = synchronized(monitor) { epochs[project] ?: 0 }
    private fun checkIdentity(project: String, owner: Long, expected: Long) {
        synchronized(monitor) { if ((epochs[project] ?: 0) != owner || revision != expected) throw McpException(McpProblem.Closed) }
    }
    private fun snapshot(server: McpServer) = server.copy(args = server.args.toList(), env = server.env.toMap(), headers = server.headers.toMap())
    private fun redactJson(value: Any, depth: Int = 0): Any = when (value) {
        is JSONObject -> JSONObject().apply { value.keys().forEach { key ->
            val envelope = depth == 0 && key in setOf("isError", "content", "structuredContent", "tools", "resources", "resourceTemplates", "prompts", "contents", "messages", "protocolVersion", "capabilities", "untrusted", "truncated", "nextCursor")
            put(if (envelope) key else redact(key), redactJson(value.get(key), depth + 1))
        } }
        is JSONArray -> JSONArray().apply { for (index in 0 until value.length()) put(redactJson(value.get(index), depth + 1)) }
        is String -> redact(value)
        else -> value
    }
    private fun begin(external: Boolean = false) = synchronized(monitor) { operations++; if (external) externalOperations++; mutable.update { it.copy(busy = true, running = externalOperations > 0, error = "", probeResult = "", probeServerId = "", probeProjectId = "") } }
    private fun end(external: Boolean = false) = synchronized(monitor) { operations--; if (external) externalOperations--; mutable.update { it.copy(busy = operations > 0, running = externalOperations > 0, loading = false) } }
    private fun operation(external: Boolean = false, block: suspend () -> Unit): Job = scope.launch {
        begin(external)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(error = context.getString(when ((failure as? McpException)?.problem) {
            McpProblem.InvalidConfig -> R.string.mcp_config_invalid
            McpProblem.Storage -> R.string.mcp_storage_error
            McpProblem.Disabled -> R.string.mcp_disabled
            McpProblem.Timeout -> R.string.mcp_timeout
            else -> R.string.mcp_operation_failed
        }), probeResult = "", probeServerId = "", probeProjectId = "") } }
        finally { end(external) }
    }
}
