package dev.zakocoder.app

import dev.zakocoder.app.ai.AiFailure
import dev.zakocoder.app.ai.AiRetryPolicy
import dev.zakocoder.app.ai.AiTransportException
import org.junit.Assert.*
import org.junit.Test

class AiHttpFailureTest {
    @Test fun paymentRequiredKeepsStatusAndUsesBillingExplanationWithoutRetry() {
        val error = AiTransportException.http(402, 1000)
        assertEquals(402, error.status)
        assertEquals(AiFailure.Billing, error.kind)
        assertEquals(R.string.ai_failure_billing, error.kind.label)
        assertEquals("http_status_402", error.diagnostic)
        assertEquals(1000L, error.retryAfterMillis)
        for (attempt in 1..3) assertNull(AiRetryPolicy.delayMillis(attempt, error, 0))
    }

    @Test fun otherHttpFailuresKeepTheirClassificationAndRetryPolicy() {
        val denied = AiTransportException.http(403)
        assertEquals(AiFailure.Http, denied.kind)
        assertNull(AiRetryPolicy.delayMillis(1, denied, 0))
        val unavailable = AiTransportException.http(503)
        assertEquals(AiFailure.Http, unavailable.kind)
        assertEquals(1000L, AiRetryPolicy.delayMillis(1, unavailable, 0))
    }
}
