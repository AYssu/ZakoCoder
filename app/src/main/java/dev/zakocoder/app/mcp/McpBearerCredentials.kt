package dev.zakocoder.app.mcp

/** Form adapter only. Persistence and transport continue to use the encrypted header map. */
internal object McpBearerCredentials {
    data class Fields(val token: String, val otherHeaders: Map<String, String>) {
        override fun toString() = "Fields(credentials=<redacted>)"
    }

    fun split(headers: Map<String, String>): Fields {
        val auth = headers.entries.singleOrNull { it.key.equals("Authorization", ignoreCase = true) }
        val value = auth?.value.orEmpty()
        val bearer = value.length > 7 && value.startsWith("Bearer ", ignoreCase = true)
        return if (bearer) Fields(value.substring(7), headers.filterKeys { !it.equals("Authorization", true) })
        else Fields("", headers)
    }

    fun headers(tokenInput: String, otherHeaders: Map<String, String>): Map<String, String> {
        var token = tokenInput.trim()
        // Accept pasting a complete value once without producing "Bearer Bearer ...".
        if (token.startsWith("Bearer ", true)) token = token.substring(7).trim()
        if (token.isEmpty()) return otherHeaders
        require(token.length <= 8185 && token.none { it.isWhitespace() || it.isISOControl() })
        require(otherHeaders.keys.none { it.equals("Authorization", true) })
        return otherHeaders + ("Authorization" to "Bearer $token")
    }
}
