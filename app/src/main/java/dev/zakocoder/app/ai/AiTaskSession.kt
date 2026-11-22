package dev.zakocoder.app.ai

import kotlinx.coroutines.*

/** Shared by a parent and its children. Null quotas allow long tasks; repeated failures still stop execution. */
internal data class AiTaskLimits(val requests: Int? = null, val tools: Int? = null,
    val elapsedMs: Long? = null, val tokens: Long? = null, val repeatedFailures: Int = 3) {
    init {
        require(requests == null || requests > 0)
        require(tools == null || tools > 0)
        require(elapsedMs == null || elapsedMs > 0)
        require(tokens == null || tokens > 0)
        require(repeatedFailures > 0)
    }
}
data class AiTaskUsage(val requests: Int = 0, val tools: Int = 0, val tokens: Long = 0,
    val estimatedRequests: Int = 0, val elapsedMs: Long = 0) {
    init { require(requests >= 0 && tools >= 0 && tokens >= 0 && estimatedRequests in 0..requests && elapsedMs >= 0) }
}
internal class AiTaskLimit(val code: String) : IllegalStateException(code)
private data class CompletedModelRequest<T>(val value: T)
private fun incrementTaskCounter(value: Int) = if (value == Int.MAX_VALUE) value else value + 1
private fun addTaskCounter(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Admission happens before requests/tools. A finished tool must still be committed before stopping. */
internal class AiTaskSession(private val limits: AiTaskLimits = AiTaskLimits(), initial: AiTaskUsage = AiTaskUsage(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clock()
    private var usage = initial
    private val completedRequests = mutableSetOf<String>()
    private var failures = 0
    private var lastFailure: String? = null
    private var repeated = false
    @Synchronized fun snapshot() = usage.copy(elapsedMs = addTaskCounter(usage.elapsedMs, (clock() - started).coerceAtLeast(0)))
    @Synchronized private fun checkShared() {
        val current = snapshot()
        when {
            repeated -> throw AiTaskLimit("repeated_tool_failure")
            limits.elapsedMs?.let { current.elapsedMs >= it } == true -> throw AiTaskLimit("elapsed")
            limits.tokens?.let { current.tokens >= it } == true -> throw AiTaskLimit("tokens")
        }
    }
    @Synchronized fun checkExecution() = checkShared()
    @Synchronized fun check() {
        checkShared()
        if (limits.requests?.let { usage.requests >= it } == true) throw AiTaskLimit("requests")
        if (limits.tools?.let { usage.tools >= it } == true) throw AiTaskLimit("tools")
    }
    suspend fun <T> modelRequest(block: suspend () -> T): T {
        check()
        val deadline = limits.elapsedMs ?: return block()
        val completed = withTimeoutOrNull((deadline - snapshot().elapsedMs).coerceAtLeast(1)) {
            CompletedModelRequest(block())
        }
        return (completed ?: throw AiTaskLimit("elapsed")).value
    }
    @Synchronized fun request() {
        check()
        usage = usage.copy(requests = incrementTaskCounter(usage.requests), estimatedRequests = incrementTaskCounter(usage.estimatedRequests))
    }
    @Synchronized fun tool() {
        checkShared()
        if (limits.tools?.let { usage.tools >= it } == true) throw AiTaskLimit("tools")
        usage = usage.copy(tools = incrementTaskCounter(usage.tools))
    }
    @Synchronized fun recordRequest(id: String, input: Long?, output: Long?, estimate: Long, estimatedOutput: Long = 0) {
        if (!completedRequests.add(id)) return
        val estimated = input == null || output == null
        val used = addTaskCounter((input ?: estimate).coerceAtLeast(0), (output ?: estimatedOutput).coerceAtLeast(0))
        usage = usage.copy(tokens = addTaskCounter(usage.tokens, used),
            estimatedRequests = (usage.estimatedRequests - if (estimated) 0 else 1).coerceAtLeast(0))
    }
    @Synchronized fun toolResult(fingerprint: String, failed: Boolean) {
        if (!failed) { failures = 0; lastFailure = null; return }
        failures = if (lastFailure == fingerprint) incrementTaskCounter(failures) else 1
        lastFailure = fingerprint
        if (failures >= limits.repeatedFailures) repeated = true
    }
}
