package dev.zakocoder.app.ai

import kotlinx.coroutines.*

/** Shared by a parent and its children. No wall clock, prompt, credential or file data in metrics. */
internal data class AiTaskLimits(val requests: Int = 80, val tools: Int = 240,
    val elapsedMs: Long = 45 * 60 * 1000L, val tokens: Long = 2_000_000, val repeatedFailures: Int = 3) {
    init { require(requests > 0 && tools > 0 && elapsedMs > 0 && tokens > 0 && repeatedFailures > 0) }
}
data class AiTaskUsage(val requests: Int = 0, val tools: Int = 0, val tokens: Long = 0,
    val estimatedRequests: Int = 0, val elapsedMs: Long = 0) {
    init { require(requests >= 0 && tools >= 0 && tokens >= 0 && estimatedRequests in 0..requests && elapsedMs >= 0) }
}
internal class AiTaskLimit(val code: String) : IllegalStateException(code)

/** Admission happens before requests/tools. A finished tool must still be committed before stopping. */
internal class AiTaskSession(private val limits: AiTaskLimits = AiTaskLimits(), initial: AiTaskUsage = AiTaskUsage(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clock()
    private var usage = initial
    private val completedRequests = mutableSetOf<String>()
    private var failures = 0
    private var lastFailure: String? = null
    private var repeated = false
    @Synchronized fun snapshot() = usage.copy(elapsedMs = usage.elapsedMs + (clock() - started).coerceAtLeast(0))
    @Synchronized private fun checkShared() {
        val current = snapshot()
        when {
            repeated -> throw AiTaskLimit("repeated_tool_failure")
            current.elapsedMs >= limits.elapsedMs -> throw AiTaskLimit("elapsed")
            current.tokens >= limits.tokens -> throw AiTaskLimit("tokens")
        }
    }
    @Synchronized fun checkExecution() = checkShared()
    @Synchronized fun check() {
        checkShared()
        if (usage.requests >= limits.requests) throw AiTaskLimit("requests")
        if (usage.tools >= limits.tools) throw AiTaskLimit("tools")
    }
    suspend fun <T> modelRequest(block: suspend () -> T): T {
        check()
        try { return withTimeout((limits.elapsedMs - snapshot().elapsedMs).coerceAtLeast(1)) { block() } }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AiTaskLimit("elapsed")
        }
    }
    @Synchronized fun request() { check(); usage = usage.copy(requests = usage.requests + 1, estimatedRequests = usage.estimatedRequests + 1) }
    @Synchronized fun tool() { checkShared(); if (usage.tools >= limits.tools) throw AiTaskLimit("tools"); usage = usage.copy(tools = usage.tools + 1) }
    @Synchronized fun recordRequest(id: String, input: Long?, output: Long?, estimate: Long, estimatedOutput: Long = 0) {
        if (!completedRequests.add(id)) return
        val estimated = input == null || output == null
        fun add(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
        val used = add((input ?: estimate).coerceAtLeast(0), (output ?: estimatedOutput).coerceAtLeast(0))
        usage = usage.copy(tokens = add(usage.tokens, used),
            estimatedRequests = (usage.estimatedRequests - if (estimated) 0 else 1).coerceAtLeast(0))
    }
    @Synchronized fun toolResult(fingerprint: String, failed: Boolean) {
        if (!failed) { failures = 0; lastFailure = null; return }
        failures = if (lastFailure == fingerprint) failures + 1 else 1
        lastFailure = fingerprint
        if (failures >= limits.repeatedFailures) repeated = true
    }
}
