package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Real controller and local TLS, with isolated history, permissions and usage files. No guest process. */
class AiUsageControllerDeviceTest {
    @Before fun foreground() = warmDeviceActivity()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        withTimeout(20000) { while (!predicate()) delay(20) }
        check(predicate()) { label }
    }
    @Test(timeout = 90000) fun waitingReadToolIsVisibleAndExecutionRetainsWallClockDayAcrossLedgerRestart() = runBlocking {
        AiDeviceTest.Fixture(base).use { fixture ->
            val isolated = object : ContextWrapper(base) {
                override fun getFilesDir() = File(fixture.root, "usage-controller-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "usage-controller-private").apply { mkdirs() }
            }
            val project = "usage-project"
            val projectDirectory = File(fixture.root, "project").apply { mkdirs() }
            withContext(Dispatchers.IO) {
                File(projectDirectory, "sample.txt").writeText("verified fixture content\n")
                AiPermissionStore(isolated).save(project, AiPolicy(AiPermission.Ask, mapOf(AiTool.ReadFile to AiPermission.Ask)))
            }
            fixture.enqueue(calls = listOf(AiCall("usage-read", "read_file", JSONObject().put("path", "sample.txt").toString())))
            fixture.enqueue("The approved file was read.")
            lateinit var controller: AiController
            main {
                controller = AiController(isolated, { requested ->
                    check(requested == project); projectDirectory
                }, { _, _ -> false }, { _, _ -> error("No shell expected") }, {}, {}, fixture.transport,
                    fixture.config, history = fixture.history)
                controller.select(project)
            }
            try {
                await("Configuration and project stores loaded") {
                    controller.state.value.configured && !controller.models.state.value.loading &&
                        !controller.state.value.permissionsLoading && !controller.state.value.skillsLoading &&
                        !controller.usageStatistics.value.loading
                }
                assertFalse(controller.usageStatistics.value.failed)
                val conversation = controller.state.value.conversationId
                val startedBeforeSend = System.currentTimeMillis()
                main { assertTrue(controller.send("Read sample.txt after requesting permission.")) }
                await("Read waits for approval") { controller.state.value.approval?.eventId != null }
                assertEquals(AiToolStatus.Waiting, controller.state.value.events.single { it.callId == "usage-read" }.status)
                await("Waiting tool is present in persistent ledger") {
                    controller.usageStatistics.value.tools.singleOrNull { it.projectId == project && it.conversationId == conversation }?.status == "Waiting"
                }
                val waitingState = AiUsageStatistics.filter(controller.usageStatistics.value, projectId = project, conversationId = conversation)
                val waitingTool = waitingState.tools.single()
                assertEquals("Waiting", waitingTool.status)
                assertFalse(waitingTool.executed)
                assertTrue("Usage timestamps must be wall-clock epoch, not elapsed uptime", waitingTool.started >= startedBeforeSend)
                assertTrue(waitingTool.started <= System.currentTimeMillis())
                val waitingSummary = AiUsageStatistics.summarize(waitingState)
                assertEquals(1, waitingSummary.toolCount)
                assertEquals(0, waitingSummary.executedToolCount)

                // The final update must preserve the original invocation day, including while awaiting user approval.
                delay(80)
                main { controller.approve(true) }
                await("Task completed") { !controller.state.value.active }
                assertEquals("", controller.state.value.error)
                val completedEvent = controller.state.value.events.single { it.callId == "usage-read" }
                assertEquals(AiToolStatus.Succeeded, completedEvent.status)
                assertTrue(completedEvent.output.contains("verified fixture content"))
                await("Successful tool state flushed") {
                    controller.usageStatistics.value.tools.singleOrNull { it.id == waitingTool.id }?.status == "Succeeded" &&
                        controller.usageStatistics.value.requests.count { it.projectId == project && it.complete } == 2
                }
                val finalTool = controller.usageStatistics.value.tools.single { it.id == waitingTool.id }
                assertTrue(finalTool.executed)
                assertEquals("Approval and finish must not move the original timestamp", waitingTool.started, finalTool.started)

                val restored = AiUsageStore(isolated).load()
                val zone = ZoneId.systemDefault()
                val day = Instant.ofEpochMilli(waitingTool.started).atZone(zone).toLocalDate()
                val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val today = AiUsageStatistics.filter(restored, dayStart, dayEnd, project, "fixture-model", conversation)
                assertEquals(1, today.tools.size)
                assertEquals(waitingTool.started, today.tools.single().started)
                assertEquals(1, AiUsageStatistics.summarize(today).executedToolCount)
                assertEquals(2, AiUsageStatistics.filter(restored, projectId = project,
                    model = "fixture-model", conversationId = conversation).requests.size)
                assertEquals(2, fixture.server.requestCount)
                assertNull("The fixture does not report usage; no Token estimate may become a billable count",
                    AiUsageStatistics.summarize(today).totalTokens)
            } finally {
                main { controller.stop() }
                await("Stopped before fixture cleanup") { !controller.state.value.active }
            }
        }
    }
}
