package dev.zakocoder.app.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LspProcessTest {
    @Test fun wrapperPidParserConsumesNoJsonRpcHeaderBytes() {
        val stream = ByteArrayInputStream("12345\nContent-Length: 2\r\n\r\n{}".toByteArray())
        assertEquals(12345, readOwnedLspPid(stream))
        assertEquals("Content-Length: 2\r\n\r\n{}", stream.readBytes().toString(Charsets.UTF_8))
    }
    @Test fun rejectsMissingInvalidOrOversizedPidBeforeGivingProcessOwnership() {
        for (text in listOf("", "0\n", "-1\n", "abc\n", "12345678901234567890\n", "123")) assertTrue(text, runCatching { readOwnedLspPid(ByteArrayInputStream(text.toByteArray())) }.isFailure)
    }
}
