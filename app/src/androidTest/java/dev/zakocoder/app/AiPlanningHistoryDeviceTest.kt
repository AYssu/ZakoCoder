package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiPlanningHistoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun planningFlagPersistsPerConversationAndLegacyHistoryDefaultsToExecution() {
        AiDeviceTest.Fixture(context).use { f ->
            val planning = AiState(planningMode = true, turn = 1, timeline = listOf(
                AiEntry("question", "分析项目", role = AiRole.User, turn = 1),
                AiEntry("answer", "先检查入口，再列修改步骤。", role = AiRole.Assistant, turn = 1)))
            val execution = AiState(planningMode = false, turn = 1, timeline = listOf(
                AiEntry("next", "开始实现", role = AiRole.User, turn = 1)))
            f.history.save("workspace", planning)
            f.history.save("workspace", execution)
            val reopened = AiHistoryStore(File(f.root, "history"))
            assertTrue(requireNotNull(reopened.load("workspace", planning.conversationId).state).planningMode)
            assertFalse(requireNotNull(reopened.load("workspace", execution.conversationId).state).planningMode)
            val record = File(f.root, "history").walkTopDown().single { it.name == "${planning.conversationId}.json" }
            val legacy = JSONObject(record.readText()).apply { remove("planningMode") }
            record.writeText(legacy.toString())
            val restored = requireNotNull(AiHistoryStore(File(f.root, "history")).load("workspace", planning.conversationId).state)
            assertFalse(restored.planningMode)
            assertEquals(planning.timeline, restored.timeline)
        }
    }

    @Test(timeout = 60000) fun controllerRestoresPlanningModeBeforeAcceptingNextTask() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val saved = AiState(planningMode = true, turn = 1, timeline = listOf(
                AiEntry("user", "只做规划", role = AiRole.User, turn = 1)))
            withContext(Dispatchers.IO) { f.history.save("planning-restore", saved) }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            var ai: AiController? = null
            try {
                withContext(Dispatchers.Main) {
                    ai = AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("Restoring planning history must not run commands") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("planning-restore")
                }
                val controller = requireNotNull(ai)
                withTimeout(30000) { while (controller.state.value.permissionsLoading || controller.state.value.restoring || !controller.state.value.configured) delay(20) }
                assertEquals(saved.conversationId, controller.state.value.conversationId)
                assertTrue(controller.state.value.planningMode)
                assertFalse(controller.state.value.active)
                assertNull(controller.state.value.approval)
                assertEquals(0, f.server.requestCount)
            } finally { withContext(Dispatchers.Main) { ai?.stop() } }
        }
    }
}
