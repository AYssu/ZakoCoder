package dev.zakocoder.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiComposerOutgoingTextTest {
    @Test
    fun quoteContextReceivesTheCurrentInput() {
        var quoteSource = ""

        val outgoing = aiComposerOutgoingText("继续检查", "第 3 轮回复") { input ->
            quoteSource = input
            "<quoted>第 3 轮回复</quoted>\n$input"
        }

        assertEquals("继续检查", quoteSource)
        assertTrue(outgoing.startsWith("<quoted>"))
        assertTrue(outgoing.endsWith("继续检查"))
    }

    @Test
    fun blankQuoteReturnsTheUnmodifiedInput() {
        var invoked = false

        val outgoing = aiComposerOutgoingText("当前输入", "  \n") {
            invoked = true
            error("Blank quotes must not take the quoted path")
        }

        assertEquals("当前输入", outgoing)
        assertFalse(invoked)
    }
}
