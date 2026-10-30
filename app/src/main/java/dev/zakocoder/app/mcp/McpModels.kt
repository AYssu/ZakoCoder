package dev.zakocoder.app.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

enum class McpTransport { Stdio, StreamableHttp, Sse }

data class McpServer(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val transport: McpTransport, val url: String = "", val command: String = "",
    val args: List<String> = emptyList(), val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(), val enabled: Boolean = false,
) {
    override fun toString() = "McpServer(id=$id, transport=$transport, enabled=$enabled, configuration=<redacted>)"
}

data class McpState(
    val servers: List<McpServer> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val running: Boolean = false, val error: String = "", val probeResult: String = "", val probeServerId: String = "", val probeProjectId: String = "",
)

enum class McpProblem { InvalidConfig, Storage, Disabled, Unavailable, Protocol, Limit, Timeout, Closed }
class McpException(val problem: McpProblem) : Exception("MCP ${problem.name}")

/** Imports a server object or an application-style mcpServers map. Neither grants approval or enablement. */
object McpConfigParser {
    private const val MAX_CONFIG = 512 * 1024
    const val DEFAULT_IMPORT_NAME = "Imported MCP"

    /** singleName overrides only an unwrapped server's name; wrapped names are never rewritten. */
    fun parse(source: String, singleName: String = ""): List<McpServer> = guarded {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG)
        McpJson.check(source)
        val root = JSONObject(source)
        if (root.has("mcpServers")) {
            require(root.keys().asSequence().toSet() == setOf("mcpServers"))
            val servers = root.get("mcpServers") as? JSONObject ?: error("object")
            require(servers.length() in 1..32)
            servers.keys().asSequence().map { name ->
                val entry = servers.get(name) as? JSONObject ?: error("object")
                server(entry, name)
            }.toList()
        } else {
            val name = singleName.trim().ifEmpty { optional(root, "name").ifBlank { DEFAULT_IMPORT_NAME } }
            listOf(server(root, name, single = true))
        }
    }
    private fun server(entry: JSONObject, name: String, single: Boolean = false): McpServer {
        val allowed = setOf("type", "transport", "url", "command", "args", "env", "headers", "enabled", "disabled", "autoApprove") +
            if (single) setOf("name") else emptySet()
        require(entry.keys().asSequence().all { it in allowed })
        // Validate even an overridden JSON name rather than accepting malformed configuration.
        if (entry.has("name")) optional(entry, "name")
        for (flag in listOf("enabled", "disabled")) if (entry.has(flag)) require(entry.get(flag) is Boolean)
        if (entry.has("autoApprove")) strings(entry.get("autoApprove"), 128)
        val type = optional(entry, "type")
        val declared = optional(entry, "transport")
        require(type.isEmpty() || declared.isEmpty() || type == declared)
        val command = optional(entry, "command")
        val transport = when ((type.ifEmpty { declared }).lowercase()) {
            "stdio" -> McpTransport.Stdio
            "http", "streamable_http", "streamable-http", "streamablehttp" -> McpTransport.StreamableHttp
            "sse" -> McpTransport.Sse
            "" -> if (command.isNotEmpty()) McpTransport.Stdio else error("transport")
            else -> error("transport")
        }
        return McpServer(name = name, transport = transport, command = command, url = optional(entry, "url"),
            args = if (entry.has("args")) strings(entry.get("args"), 128) else emptyList(),
            env = if (entry.has("env")) stringMap(entry.get("env")) else emptyMap(),
            headers = if (entry.has("headers")) stringMap(entry.get("headers")) else emptyMap()).also(::validate)
    }
    internal fun validate(server: McpServer) = guarded {
        require(server.id.length in 1..80 && server.id.all { it.isLetterOrDigit() || it in "-_" })
        require(server.name.isNotBlank() && server.name.length <= 100 && server.name.none(Char::isISOControl))
        require(server.args.size <= 128 && server.args.all { it.length <= 8192 && '\u0000' !in it })
        require(server.env.size <= 64 && server.headers.size <= 64)
        require(server.env.all { (key, value) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value.length <= 8192 && '\u0000' !in value })
        val reserved = setOf("host", "content-length", "content-type", "accept", "connection", "mcp-session-id", "mcp-protocol-version", "origin")
        require(server.headers.all { (key, value) -> key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && key.lowercase() !in reserved && value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' } })
        require(server.headers.keys.map(String::lowercase).distinct().size == server.headers.size)
        if (server.transport == McpTransport.Stdio) {
            require(server.command.isNotBlank() && !server.command.startsWith('-') && server.command.length <= 2048 && server.command.none(Char::isISOControl))
            require(server.url.isEmpty() && server.headers.isEmpty())
        } else {
            endpoint(server.url)
            require(server.command.isEmpty() && server.args.isEmpty() && server.env.isEmpty())
        }
    }
    fun endpoint(value: String, allowQuery: Boolean = false): URI = guarded {
        require(value.length in 1..2048 && value.none(Char::isISOControl))
        val uri = URI(value)
        require(uri.userInfo == null && (allowQuery || uri.query == null) && uri.fragment == null && !uri.host.isNullOrBlank())
        require(uri.port == -1 || uri.port in 1..65535)
        require(uri.scheme == "https" || uri.scheme == "http" && McpCleartextHosts.allowed(uri.host))
        uri
    }
    internal fun sameOrigin(first: String, second: String): Boolean {
        val a = endpoint(first); val b = endpoint(second, allowQuery = true)
        fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return a.scheme == b.scheme && a.host.equals(b.host, true) && port(a) == port(b)
    }
    private fun optional(json: JSONObject, field: String): String = if (json.has(field)) json.get(field) as? String ?: error("string") else ""
    private fun strings(value: Any, max: Int): List<String> {
        val values = value as? JSONArray ?: error("array")
        require(values.length() <= max)
        return (0 until values.length()).map { values.get(it) as? String ?: error("string") }
    }
    private fun stringMap(value: Any): Map<String, String> {
        val fields = value as? JSONObject ?: error("object")
        require(fields.length() <= 64)
        return fields.keys().asSequence().associateWith { fields.get(it) as? String ?: error("string") }
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (failure: McpException) { throw failure }
        catch (_: Exception) { throw McpException(McpProblem.InvalidConfig) }
}
