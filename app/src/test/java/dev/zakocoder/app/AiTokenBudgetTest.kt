package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiTokenBudgetTest {
    @Test fun estimatesAreNotCharacterCountsAndHandleUnicodeCodePoints() {
        assertEquals(100L, estimateAiTokens("a".repeat(300)))
        assertEquals(200L, estimateAiTokens("中".repeat(100)))
        assertEquals(2L, estimateAiTokens("😀"))
        assertTrue(estimateAiTokens("hello中文") > estimateAiTokens("hello"))
    }
    @Test fun thresholdReservesOutputBeforeCompressing() {
        val below = AiTokenBudget(100000, 128000, 8192, 90)
        assertFalse(below.shouldCompress); assertTrue(below.fits)
        val nearing = below.copy(inputTokens = 110000)
        assertTrue(nearing.shouldCompress); assertTrue(nearing.fits)
        assertFalse(below.copy(inputTokens = 120000).fits)
        assertFalse(below.copy(windowTokens = 8192, outputReserveTokens = 32768).fits)
    }
    @Test fun millionTokenWindowAndExplicitRangeAreSupported() {
        AiContextOptions(contextWindowTokens = 1048576).validate()
        AiContextOptions(contextWindowTokens = 2097152).validate()
        assertThrows(IllegalArgumentException::class.java) { AiContextOptions(contextWindowTokens = 8191).validate() }
        assertThrows(IllegalArgumentException::class.java) { AiContextOptions(contextWindowTokens = 2097153).validate() }
        assertEquals(128000, AiContextOptions().contextWindowTokens)
        assertEquals(90, AiContextOptions().compressionThresholdPercent)
    }
    @Test fun summaryOutputNeverUsesMoreSpaceThanTheNormalOutputReservation() {
        val smallWindow = AiConfig(context = AiContextOptions(contextWindowTokens = 8192))
        assertEquals(2048, aiOutputReserve(smallWindow))
        assertEquals(2048, aiSummaryOutputReserve(smallWindow))
        val explicit = smallWindow.copy(parameters = AiParameters(maxTokens = 512))
        assertEquals(512, aiSummaryOutputReserve(explicit))
        assertEquals(4000, aiSummaryOutputReserve(AiConfig()))
    }

}
