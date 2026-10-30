package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiRetryPolicyTest {
    @Test fun retriesOnlyTransientErrorsWithinThreeAttempts() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val error = AiTransportException(status, AiFailure.Http, "http_status_$status")
            assertEquals(1000L, AiRetryPolicy.delayMillis(1, error, 0))
            assertEquals(2250L, AiRetryPolicy.delayMillis(2, error, 999))
            assertNull(AiRetryPolicy.delayMillis(3, error, 0))
        }
        for (status in listOf(400, 401, 403, 404, 413, 422, 501))
            assertNull(AiRetryPolicy.delayMillis(1, AiTransportException(status, AiFailure.Http), 0))
        for ((kind, diagnostic) in listOf(AiFailure.Network to "connect_failed", AiFailure.Network to "stream_eof",
            AiFailure.IdleTimeout to "network_read_or_connect_timeout", AiFailure.Protocol to "missing_done", AiFailure.Protocol to "responses_server_error",
            AiFailure.Protocol to "responses_rate_limit", AiFailure.Protocol to "anthropic_overloaded",
            AiFailure.Protocol to "anthropic_rate_limit", AiFailure.Protocol to "gemini_unavailable", AiFailure.Protocol to "gemini_rate_limit"))
            assertNotNull(AiRetryPolicy.delayMillis(1, AiTransportException(kind = kind, diagnostic = diagnostic), 0))
        for ((kind, diagnostic) in listOf(AiFailure.Network to "tls_failed", AiFailure.Network to "invalid_proxy",
            AiFailure.Cancelled to "cancelled", AiFailure.Protocol to "malformed_json", AiFailure.Protocol to "invalid_tool_identity",
            AiFailure.OutputLimit to "finish_length", AiFailure.Protocol to "provider_error_event"))
            assertNull(AiRetryPolicy.delayMillis(1, AiTransportException(kind = kind, diagnostic = diagnostic), 0))
    }

    @Test fun retryAfterNeverRetriesEarlierThanTheServerAndRejectsLongOrInvalidWaits() {
        val error = AiTransportException(429, AiFailure.Http, "http_status_429", 7000)
        assertEquals(7000L, AiRetryPolicy.delayMillis(1, error, 0))
        assertNull(AiRetryPolicy.delayMillis(1, AiTransportException(429, AiFailure.Http, "http_status_429", 30001), 0))
        assertNull(AiRetryPolicy.delayMillis(1, AiTransportException(429, AiFailure.Http, "http_status_429", -1), 0))
        assertNull(AiRetryPolicy.retryAfterMillis(null))
        assertEquals(5000L, AiRetryPolicy.retryAfterMillis("5"))
        assertEquals(-1L, AiRetryPolicy.retryAfterMillis("31"))
        assertEquals(-1L, AiRetryPolicy.retryAfterMillis("invalid"))
        assertEquals(-1L, AiRetryPolicy.retryAfterMillis("999999999999999999999999999"))
        assertEquals(5000L, AiRetryPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:00:05 GMT", 0))
        assertEquals(0L, AiRetryPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:00:00 GMT", 1000))
    }
}
