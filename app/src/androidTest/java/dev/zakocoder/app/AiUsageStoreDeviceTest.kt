package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AiUsageStoreDeviceTest {
    private fun fixture(block: (android.content.Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "usage-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        try { block(context, directory) } finally { directory.deleteRecursively() }
    }
    private fun request(id: String = "request", started: Long = 1000, project: String = "project", complete: Boolean = false) =
        AiUsageRequest(id, project, "conversation", "test-model", started, complete, false, null, null, null, 123, 128000, false)
    @Test fun finalUpsertAndRestartPreserveIndependentProjectAccounting() = fixture { context, _ -> runBlocking {
        val store = AiUsageStore(context) { 2000 }
        store.record(request())
        store.record(request(complete = true).copy(inputTokens = 200, outputTokens = 30, cachedTokens = 100))
        store.record(request()) // delayed initial callback cannot downgrade completed usage
        store.record(request(project = "other", complete = true).copy(inputTokens = 50))
        val event = AiUsageTool("tool", "project", "conversation", "test-model", 1000, "Failed", true)
        store.record(event); store.record(event)
        val restored = AiUsageStore(context) { 3000 }.load()
        assertEquals(2, restored.requests.size); assertEquals(1, restored.tools.size)
        val summary = AiUsageStatistics.summarize(AiUsageStatistics.filter(restored, projectId = "project"))
        assertEquals(1, summary.requestCount); assertEquals(0, summary.pendingRequestCount)
        assertEquals(230L, summary.totalTokens); assertEquals(100L, summary.cachedTokens)
        assertEquals(2000L, restored.startedAt)
    } }
    @Test fun incompleteMarkerSurvivesRecoveryRefreshAndRestart() = fixture { context, _ -> runBlocking {
        val store = AiUsageStore(context) { 2000 }
        val incomplete = store.record(listOf(request()), emptyList(), incomplete = true)
        assertTrue(incomplete.incomplete)
        assertFalse(incomplete.failed)
        assertTrue(store.record(request("second")).incomplete)
        assertTrue(store.load().incomplete)
        val restored = AiUsageStore(context) { 3000 }.load()
        assertTrue(restored.incomplete)
        assertTrue(AiUsageStatistics.filter(restored, projectId = "other").incomplete)
    } }
    @Test fun ledgerDoesNotImportConversationHistoryAndCorruptionIsNotOverwritten() = fixture { context, directory -> runBlocking {
        val file = File(directory, "ai-usage-v1.json")
        File(directory, "ai-history-v1").mkdirs()
        assertTrue(AiUsageStore(context).load().requests.isEmpty())
        val broken = "{invalid-ledger"; file.writeText(broken)
        try { AiUsageStore(context).record(request()); fail("Corrupt ledger must fail closed") } catch (_: org.json.JSONException) { }
        assertEquals(broken, file.readText())
    } }
    @Test fun retentionAndRecordCapsAreExplicitAfterRestart() = fixture { context, _ -> runBlocking {
        var time = 2000L
        val store = AiUsageStore(context) { time }
        store.record((0..10000).map { request("r$it", started = 1000L + it) }, emptyList())
        var state = store.load()
        assertEquals(10000, state.requests.size); assertTrue(state.truncated); assertNotNull(state.retainedFrom)
        time = 731L * 24 * 60 * 60 * 1000
        state = AiUsageStore(context) { time }.load()
        assertTrue(state.requests.isEmpty()); assertTrue(state.truncated); assertNotNull(state.retainedFrom)
    } }
}
