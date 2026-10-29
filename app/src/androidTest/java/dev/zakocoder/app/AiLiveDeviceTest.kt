package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Manually opted-in only. Loads the device configuration internally; never exports keys or response bodies. */
class AiLiveDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val registry get() = File(context.noBackupFilesDir, "ai-live-acceptance-owned.json")
    private val scratch get() = File(context.cacheDir, "ai-live-acceptance-owned").apply { mkdirs() }
    private val isolated get() = object : ContextWrapper(context) { override fun getFilesDir(): File = scratch }
    private val history get() = AiHistoryStore(context)
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(150000) { while (!condition()) delay(40) }
    private fun controller() = AiController(isolated, { scratch }, { _, _ -> false }, { _, _ -> error("Live probe forbids commands") }, {}, {},
        config = AiConfigStore(context), permissionStore = AiPermissionStore(isolated), history = history)
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("realService") == "true")

    @Test(timeout = 180000) fun writeLiveMarkdownBeforeProcessDeath() = runBlocking {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            check(!registry.exists()) { "Complete or clean up the previous owned probe first" }
            val project = "live-acceptance-${UUID.randomUUID()}"
            val marker = "cedar-${UUID.randomUUID().toString().take(8)}"
            registry.writeText(JSONObject().put("project", project).put("marker", marker).toString())
            lateinit var ai: AiController
            main { ai = controller(); ai.select(project) }
            await { ai.state.value.configured && !ai.state.value.permissionsLoading }
            main { ai.setPolicy(AiPolicy(AiPermission.Deny, emptyMap())) }; await { !ai.state.value.permissionsSaving }
            var increments = 0; var previous = 0; var first = 0L
            val start = android.os.SystemClock.elapsedRealtime()
            val observer = launch(Dispatchers.Default) { ai.state.collect { state ->
                val length = state.timeline.filter { e -> e.role == AiRole.Assistant }.sumOf { e -> e.text.length }
                if (length > previous) { increments++; previous = length; if (first == 0L) first = android.os.SystemClock.elapsedRealtime() - start }
            } }
            try {
                main { ai.send("这是客户端验收，请不要调用任何工具。记住标记 $marker。用中文回复一个 Markdown 二级标题、一段加粗文字、一个两步列表和一段 Kotlin 代码块，总共不超过 250 字。") }
                await { !ai.state.value.active }
                observer.cancelAndJoin()
                assertEquals("Live request must complete: ${ai.state.value.failureKind}; ${ai.state.value.requests.lastOrNull()?.status}; ${ai.state.value.requests.lastOrNull()?.diagnostic}", AiPhase.Completed, ai.state.value.phase)
                assertFalse(ai.state.value.historyError)
                assertEquals(1, ai.state.value.contextTurns.size)
                assertTrue(ai.state.value.timeline.any { e -> e.role == AiRole.Assistant && e.text.isNotBlank() })
                assertTrue(ai.state.value.events.isEmpty())
                registry.writeText(JSONObject().put("project", project).put("marker", marker).put("conversation", ai.state.value.conversationId).toString())
                instrumentation.sendStatus(0, Bundle().apply { putString("liveUsage", ai.state.value.requests.last().let { "HTTP=${it.status}; input=${it.inputTokens}; output=${it.outputTokens}; cached=${it.cachedTokens}; events=${it.events}; bytes=${it.bytes}; diagnostic=${it.diagnostic}" }); putString("liveObservation", "state increments=$increments; firstContentMs=$first; totalMs=${android.os.SystemClock.elapsedRealtime() - start}") })
            } finally { observer.cancelAndJoin(); main { ai.stop() }; await { !ai.state.value.active } }
        }
    }

    @Test(timeout = 180000) fun restoreAfterProcessDeathAndAskFollowup() = runBlocking {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            val owned = JSONObject(registry.readText()); val project = owned.getString("project")
            var instance: AiController? = null
            try {
                main { instance = controller(); instance!!.select(project) }
                val ai = instance!!
                await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                assertEquals(owned.getString("conversation"), ai.state.value.conversationId)
                assertEquals(1, ai.state.value.contextTurns.size)
                assertFalse(ai.state.value.active); assertNull(ai.state.value.approval)
                assertTrue(ai.state.value.timeline.any { e -> e.role == AiRole.Assistant && e.text.isNotBlank() })
                main { ai.send("不要使用工具，只回复我上一轮要求记住的完整标记，不要解释。") }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertFalse(ai.state.value.historyError)
                assertEquals(2, ai.state.value.contextTurns.size)
                assertTrue(ai.state.value.timeline.last { e -> e.role == AiRole.Assistant }.text.contains(owned.getString("marker")))
                assertTrue(ai.state.value.events.isEmpty())
            } finally {
                instance?.let { active -> main { active.stop() }; await { !active.state.value.active } }
                withContext(Dispatchers.IO) { history.deleteProject(project); registry.delete(); scratch.deleteRecursively() }
            }
        }
    }
}
