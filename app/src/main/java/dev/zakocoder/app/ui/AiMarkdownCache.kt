package dev.zakocoder.app.ui

import org.commonmark.node.Node

/** Immutable render trees only; bounded in-memory reuse, never persisted with project data. */
internal object AiMarkdownCache {
    private const val MAX_DOCUMENTS = 12
    private const val MAX_CHARACTERS = 192_000
    private val documents = LinkedHashMap<String, Node>(16, 0.75f, true)
    private var characters = 0

    @Synchronized fun get(text: String): Node? = documents[text]

    /** Invoke on a background dispatcher. CommonMark trees are read-only after publication. */
    fun parse(text: String): Node {
        get(text)?.let { return it }
        val parsed = AiMarkdownDocument.parse(text)
        synchronized(this) {
            documents[text]?.let { return it }
            documents[text] = parsed
            characters += text.length
            while (documents.size > MAX_DOCUMENTS || characters > MAX_CHARACTERS) {
                val iterator = documents.entries.iterator()
                characters -= iterator.next().key.length
                iterator.remove()
            }
        }
        return parsed
    }
}
