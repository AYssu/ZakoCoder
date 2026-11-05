package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiModelProbeChecksTest {
    @Test fun successfulHttpWithoutExpectedToolsDoesNotVerifyTools() {
        val checks = AiModelProbeChecks.from(AiModelsState(probe = AiProbe.Failed,
            probeProgress = AiModelProbeProgress(status = 200, textChars = 42)))
        assertTrue(checks.connected)
        assertFalse(checks.toolsVerified)
    }

    @Test fun authenticationFailureAndInterruptedDraftsDoNotPassToolChecks() {
        for (probe in listOf(AiProbe.Failed, AiProbe.Cancelled, AiProbe.Testing)) {
            val checks = AiModelProbeChecks.from(AiModelsState(probe = probe,
                probeProgress = AiModelProbeProgress(status = 401, argumentChars = 10)))
            assertFalse(checks.connected)
            assertFalse(checks.toolsVerified)
        }
        assertFalse(AiModelProbeChecks.from(AiModelsState()).connected)
    }

    @Test fun onlyValidatedToolReplyPassesBothChecks() {
        val checks = AiModelProbeChecks.from(AiModelsState(probe = AiProbe.Passed))
        assertTrue(checks.connected)
        assertTrue(checks.toolsVerified)
    }
}
