package dev.zakocoder.app.editor.lsp

import dev.zakocoder.app.runtime.LspProcess
import dev.zakocoder.app.runtime.RuntimeCommand
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Sora owns the JSON-RPC framing; this adapter owns exactly one guest process. */
internal class UbuntuLspConnection(command: RuntimeCommand, hostDirectory: File, stderr: (String) -> Unit, private val adapter: LspWireAdapter) : StreamConnectionProvider {
    private val process = LspProcess(command, hostDirectory, stderr)
    val isAlive: Boolean get() = process.isAlive
    private lateinit var incoming: InputStream
    private lateinit var outgoing: OutputStream
    override fun start() {
        process.start()
        // Pinned Sora 0.23.6/LSP4J 0.24.0 invoke notification handlers synchronously
        // before reading the next frame (StreamMessageProducer -> RemoteEndpoint ->
        // GenericEndpoint -> DefaultLanguageClient). Keep this boundary unbuffered:
        // pre-handler diagnostic replacement must precede exactly that notification.
        incoming = LspFrameInput(process.input, adapter::incoming)
        outgoing = LspFrameOutput(process.output, adapter::outgoing)
    }
    override val inputStream: InputStream get() = incoming
    override val outputStream: OutputStream get() = outgoing
    override fun close() = process.close()
}
