package dev.zakocoder.app

import dev.zakocoder.app.screentest.boundedOverlayText
import dev.zakocoder.app.screentest.screenActionTimeoutMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenTestReliabilityPolicyTest {
    @Test fun composedActionsHaveRoomForHashingAndSeveralBoundedRootCommands() {
        listOf("observe", "click", "input_text", "install", "launch").forEach {
            assertEquals(it, 60_000L, screenActionTimeoutMillis(it))
        }
        assertEquals(20_000L, screenActionTimeoutMillis("finish"))
    }

    @Test fun overlayNeverSplitsSupplementaryCharactersAtItsBound() {
        assertEquals("x".repeat(1199), boundedOverlayText("x".repeat(1199) + "😀tail"))
        val text = boundedOverlayText("😀".repeat(1000))
        assertEquals(1200, text.length)
        assertFalse(Character.isHighSurrogate(text.last()))
        assertEquals("实际消息", boundedOverlayText("实际消息"))
    }
}
