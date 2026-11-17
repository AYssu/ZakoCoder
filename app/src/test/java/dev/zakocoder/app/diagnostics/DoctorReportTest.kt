package dev.zakocoder.app.diagnostics

import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.mcp.*
import dev.zakocoder.app.ssh.*
import org.junit.Assert.*
import org.junit.Test

class DoctorReportTest {
    @Test fun exportProjectsOnlyTypedMetadataEvenWhenEveryTextFieldContainsSensitiveMaterial() {
        val secret = "private-fixture-content-never-export"
        val request = AiRequestLog(id = "local-request", model = secret, origin = "https://$secret.invalid",
            request = secret, response = secret, diagnostic = secret, finish = secret,
            retryReason = secret, agent = secret, apiProtocol = "Responses", status = 200,
            events = 23, bytes = 1234, inputTokens = 200, outputTokens = 10, complete = true)
        val state = AiState(projectId = secret, conversationId = secret, messages = listOf(secret),
            taskPrompt = secret, contextSummary = secret, taskDiagnostic = secret, error = secret,
            endpoint = secret, model = secret, requests = listOf(request), active = true,
            phase = AiPhase.Executing, failureKind = AiFailure.Network,
            events = listOf(AiToolEvent(secret, secret, AiTool.RunCommand, secret, secret, secret, secret,
                preview = secret, output = secret, status = AiToolStatus.Succeeded)))
        val report = DoctorReport.ai(state, System.currentTimeMillis())
        assertFalse(report.toString().contains(secret))
        assertEquals("Executing", report.getString("phase"))
        val recorded = report.getJSONArray("requests").getJSONObject(0)
        assertEquals(200, recorded.getInt("httpStatus"))
        assertEquals(23, recorded.getInt("events"))
        assertEquals("Responses", recorded.getString("protocol"))
        assertEquals("Network", report.getString("failure"))
        assertFalse(recorded.has("request"))
        assertFalse(recorded.has("response"))
    }

    @Test fun componentNamesAndCredentialsNeverEnterReport() {
        val secret = "private-component-fixture"
        val report = DoctorReport.components(WorkbenchState(log = secret, message = secret, operation = secret,
            proxyDisplay = secret, toolCheckError = secret),
            McpState(servers = listOf(McpServer(name = secret, transport = McpTransport.StreamableHttp,
                url = "https://$secret.invalid", headers = mapOf("Authorization" to secret), enabled = true)),
                connections = mapOf(secret to McpConnection(McpConnectionStatus.Failed, secret)), error = secret),
            SshState(profiles = listOf(SshProfile(secret, secret, username = secret, workingDirectory = secret)), error = secret))
        assertFalse(report.toString().contains(secret))
        assertEquals(1, report.getJSONObject("mcp").getInt("failed"))
        assertTrue(report.getJSONObject("ssh").getBoolean("hasError"))
    }

    @Test fun requestAndToolSnapshotsHaveIndependentBounds() {
        val state = AiState(requests = List(50) { AiRequestLog(round = it) },
            events = List(200) { AiToolEvent("event-$it", "call-$it", null, "", "", "", "") })
        val report = DoctorReport.ai(state, System.currentTimeMillis())
        assertEquals(32, report.getJSONArray("requests").length())
        assertEquals(128, report.getJSONArray("tools").length())
        assertEquals(18, report.getJSONArray("requests").getJSONObject(0).getInt("round"))
    }
}
