package dev.zakocoder.app.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProjectGitAdmissionTest {
    @Test fun activeOperationRejectsARepeatedAdmissionAndStopCancelsItsRecordedJob() = runBlocking {
        val gate = GitOperationGate()
        val started = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.LAZY) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                coroutineContext[Job]?.let(gate::finished)
            }
        }
        assertTrue(gate.start { first })
        started.await()

        val rejectedFactoryCalls = AtomicInteger()
        assertFalse(gate.start {
            rejectedFactoryCalls.incrementAndGet()
            launch(start = CoroutineStart.LAZY) { }
        })
        assertEquals(0, rejectedFactoryCalls.get())

        gate.cancel()
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(gate.start { launch(start = CoroutineStart.LAZY) { } })
    }
}
