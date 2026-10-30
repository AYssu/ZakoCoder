package dev.zakocoder.app.mcp

/** Literal private IPv4 or loopback only: no DNS, abbreviated IPs, or lookalike hostnames. */
internal object McpCleartextHosts {
    fun allowed(host: String): Boolean {
        if (host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                part.length > 1 && part.startsWith('0')) return false
            part.toInt().takeIf { it in 0..255 } ?: return false
        }
        return bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 ||
            bytes[0] == 192 && bytes[1] == 168
    }
}
