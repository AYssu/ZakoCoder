package dev.zakocoder.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only an oversized, otherwise valid summary gets one replacement; never truncate evidence. */
internal suspend fun correctOversizedSummary(initial: AiReply, maxChars: Int,
    correct: suspend (String) -> AiReply): AiReply {
    currentCoroutineContext().ensureActive()
    if (initial.calls.isNotEmpty() || initial.text.isBlank()) throw AiBudgetException(AiFailure.Compaction)
    if (initial.text.length <= maxChars) return initial
    val replacement = try { correct(initial.text) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (transport: AiTransportException) { throw transport }
    catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        throw AiBudgetException(AiFailure.Compaction)
    }
    currentCoroutineContext().ensureActive()
    if (replacement.calls.isNotEmpty() || replacement.text.isBlank() || replacement.text.length > maxChars)
        throw AiBudgetException(AiFailure.Compaction)
    return replacement
}
