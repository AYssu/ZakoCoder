package dev.zakocoder.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FatalMarkerTest {
    @Test fun recordContainsOnlyMetadataAndNeverThrowableTextOrPaths() {
        val failure = captureStackSentinel()
        val stackMethod = failure.stackTrace.first().methodName
        val encoded = encodeFatalRecord(1234L, failure, Thread.currentThread(), "startup")
        val record = JSONObject(encoded)

        assertEquals(setOf("time", "exceptionClass", "thread", "stage"), record.keys().asSequence().toSet())
        assertEquals(1234L, record.getLong("time"))
        assertEquals(IllegalStateException::class.java.name, record.getString("exceptionClass"))
        assertEquals(Thread.currentThread().name, record.getString("thread"))
        assertEquals("startup", record.getString("stage"))

        assertTrue(failure.stackTraceToString().contains(stackMethod))
        assertFalse(encoded.contains("private-message"))
        assertFalse(encoded.contains("/sdcard/private/project"))
        assertFalse(encoded.contains("https://private.invalid"))
        assertFalse(encoded.contains("secret-token"))
        assertFalse(encoded.contains(stackMethod))
        assertFalse(encoded.contains("FatalMarkerTest"))
    }

    @Test fun stageIsOptionalButRemainsTheOnlyAdditionalField() {
        val record = JSONObject(encodeFatalRecord(1L, IllegalStateException(), Thread.currentThread()))

        assertEquals(setOf("time", "exceptionClass", "thread"), record.keys().asSequence().toSet())
        assertFalse(record.has("stage"))
    }

    @Test fun rotationStartsOnlyAfterThe64KibBoundary() {
        assertFalse(shouldRotate(FatalMarker.MAX_FILE_BYTES - 1, 1))
        assertTrue(shouldRotate(FatalMarker.MAX_FILE_BYTES, 1))
        assertTrue(shouldRotate(0, FatalMarker.MAX_FILE_BYTES.toInt() + 1))
    }

    private fun captureStackSentinel(): Throwable = IllegalStateException(
        "private-message /sdcard/private/project https://private.invalid secret-token",
    )
}
