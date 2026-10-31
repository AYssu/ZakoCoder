package dev.zakocoder.app.ai

import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.EOFException

/** Data framing for one HTTP response; protocol completion is validated by the owning decoder. */
internal class AiSseReader(private val source: BufferedSource, private val checkActive: () -> Unit = {}) {
    private var firstLine = true
    private var skipLf = false

    /** One blank-line-delimited data payload. EOF never completes a pending frame. */
    fun next(): String? {
        val data = StringBuilder()
        var hasData = false
        var bytes = 0L
        while (true) {
            checkActive()
            var line = line() ?: run {
                if (hasData) throw EOFException("Incomplete SSE event")
                return null
            }
            if (firstLine) { firstLine = false; line = line.removePrefix("\uFEFF") }
            if (line.isEmpty()) {
                if (hasData) return data.toString()
                continue
            }
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            if (field != "data") continue
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            // Keep the existing native-adapter budget: each data line includes one separator byte.
            bytes += value.toByteArray(Charsets.UTF_8).size + 1L
            if (bytes > MAX_BYTES) limit()
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
    }

    /** CR, LF and CRLF are legal; scan buffered segments without per-byte decoding or unbounded reads. */
    private fun line(): String? {
        val pending = Buffer()
        while (true) {
            checkActive()
            if (!source.request(1)) {
                if (pending.size > 0) throw EOFException("Incomplete SSE line")
                return null
            }
            if (skipLf) {
                skipLf = false
                if (source.buffer[0] == 10.toByte()) { source.skip(1); continue }
            }
            val end = source.buffer.indexOfElement(LINE_ENDINGS)
            val count = if (end < 0) source.buffer.size else end
            if (pending.size + count > MAX_BYTES) limit()
            source.read(pending, count)
            if (end >= 0) {
                skipLf = source.readByte() == 13.toByte()
                return pending.readUtf8()
            }
        }
    }

    private fun limit(): Nothing = throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_event_limit")

    companion object {
        private const val MAX_BYTES = 8L * 1024 * 1024
        private val LINE_ENDINGS = "\r\n".encodeUtf8()
    }
}
