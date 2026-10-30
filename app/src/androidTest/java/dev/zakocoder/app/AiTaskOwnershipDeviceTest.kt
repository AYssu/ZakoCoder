package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Shared Git/task ownership must reject new AI tasks even between process-level busy intervals. */
class AiTaskOwnershipDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(25) }

    @Test(timeout = 90000)
    fun ownedProjectOperationRejectsWithoutConsumingPromptThenSamePromptCanRunAfterRelease() = runBlocking {
        AiDeviceTest.Fixture(context).use { fixture ->
            val applicationFiles = File(fixture.root, "application").apply { mkdirs() }
            val projectFiles = File(fixture.root, "project").apply { mkdirs() }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = applicationFiles
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            }
            val available = AtomicBoolean(false)
            var controller: AiController? = null
            try {
                main {
                    controller = AiController(isolated, { projectFiles }, { _, _ -> false },
                        { _, _ -> error("No process is expected") }, {}, {}, fixture.transport, fixture.config,
                        permissionStore = AiPermissionStore(isolated, "task-ownership-permissions"),
                        history = fixture.history, canStartTask = available::get)
                    controller!!.select("task-ownership-fixture")
                }
                val ai = requireNotNull(controller)
                await { ai.state.value.configured && !ai.models.state.value.loading && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                val before = ai.state.value
                val prompt = "保留这条输入，等 Git 拉取结束后再处理。"
                main { assertFalse(ai.send(prompt)) }
                val rejected = ai.state.value
                assertEquals(context.getString(R.string.project_other_busy), rejected.error)
                assertFalse(rejected.active)
                assertEquals(before.turn, rejected.turn)
                assertEquals(before.timeline, rejected.timeline)
                assertEquals(before.references, rejected.references)
                assertEquals(before.pendingImages, rejected.pendingImages)
                assertEquals(before.checkpointId, rejected.checkpointId)
                assertEquals(before.taskPrompt, rejected.taskPrompt)
                assertEquals(0, fixture.server.requestCount)
                assertTrue(ai.checkpointList().isEmpty())
                fixture.enqueue("Git 操作结束后，这条输入已成功处理。")
                available.set(true)
                main { assertTrue(ai.send(prompt)) }
                await { !ai.state.value.active }
                assertEquals(1, fixture.server.requestCount)
                assertEquals(before.turn + 1, ai.state.value.turn)
                assertEquals("", ai.state.value.error)
                assertEquals(prompt, ai.state.value.timeline.first { it.role == AiRole.User }.text)
                assertEquals(AiPhase.Completed, ai.state.value.phase)
            } finally {
                main { controller?.stop() }
                await { controller?.state?.value?.active != true }
            }
        }
    }
}
