package dev.zakocoder.app.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Platform-independent model/tool loop. The session adapter owns wire messages, persistence,
 * approvals and side effects. This runner owns their order and never retries tool execution.
 *
 * A request adapter may retry transport, but must not return a partial response. A tool result
 * is committed synchronously before another tool or model request can run. Exceptions (including
 * cancellation and a question pause) propagate to the session owner for durable recovery.
 */
internal class AgentRunner {
    suspend fun <Reply, Call, Event, Result> run(
        request: suspend () -> Reply,
        accept: suspend (Reply) -> Unit,
        calls: (Reply) -> List<Call>,
        complete: suspend (Reply) -> Boolean,
        prepareTools: suspend (Reply, List<Call>) -> List<Event>,
        execute: suspend (Reply, Call, Event) -> Result,
        commit: (Reply, Call, Event, Result) -> Unit,
        finishTools: suspend () -> Unit = {},
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val reply = request()
            // Late, non-cooperative transport completion must never execute tools after Stop.
            currentCoroutineContext().ensureActive()
            accept(reply)
            val batch = calls(reply)
            if (batch.isEmpty()) {
                // A session may have received a user steering message at this safe boundary.
                if (complete(reply)) return
                continue
            }
            val events = prepareTools(reply, batch)
            check(events.size == batch.size) { "Every tool call requires its own event" }
            batch.indices.forEach { index ->
                currentCoroutineContext().ensureActive()
                val result = execute(reply, batch[index], events[index])
                // No suspension or cancellation check between a completed operation and its
                // result. Cancellation cannot leave a successful side effect unpaired here.
                commit(reply, batch[index], events[index], result)
            }
            finishTools()
        }
    }
}
