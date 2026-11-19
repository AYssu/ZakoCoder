package dev.zakocoder.app

import dev.zakocoder.app.screentest.screenActionTimeoutMillis
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTestReliabilityPolicyTest {
    @Test fun virtualActionsHaveRoomForSeveralBoundedRootOperations() {
        listOf("observe", "click", "input_text", "launch", "screenshot").forEach {
            assertEquals(it, 60_000L, screenActionTimeoutMillis(it))
        }
        assertEquals(20_000L, screenActionTimeoutMillis("finish"))
        assertEquals(20_000L, screenActionTimeoutMillis("list_apps"))
    }
}
