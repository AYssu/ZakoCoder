package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.memory.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MemoryRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "memory-test-${UUID.randomUUID()}")
    private val repository = MemoryRepository(directory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun cleanUp() { scope.cancel(); directory.deleteRecursively() }

    @Test fun confirmedMemoryPersistsAndCanBeEditedDisabledAndDeleted() = runBlocking {
        val controller = MemoryController(repository, scope)
        controller.load().join()
        controller.add("语言", "请用中文回复", core = true).join()
        assertNull(controller.state.value.error)
        val entry = repository.load().single()
        assertEquals("请用中文回复", controller.contextFor("hello").core.single().content)
        controller.update(entry.id, "语言偏好", "请简明地用中文回复", true).join()
        assertEquals("语言偏好", MemoryRepository(directory).load().single().title)
        controller.setEnabled(entry.id, false).join()
        assertTrue(controller.contextFor("语言").entries.isEmpty())
        controller.remove(entry.id).join()
        assertTrue(repository.load().isEmpty())
    }

    @Test fun aiSuggestionsRequireExplicitConfirmationAndSurviveRestart() = runBlocking {
        val controller = MemoryController(repository, scope)
        controller.load().join()
        controller.propose("构建", "使用 ./gradlew assembleDebug 验证", "conversation:test-task").join()
        assertEquals(1, controller.state.value.proposals.size)
        assertTrue(controller.contextFor("构建").entries.isEmpty())
        val restored = MemoryController(MemoryRepository(directory), scope)
        restored.load().join()
        assertEquals(1, restored.state.value.proposals.size)
        restored.acceptProposal(restored.state.value.proposals.single().id).join()
        assertEquals(1, restored.contextFor("构建").relevant.size)
    }

    @Test fun corruptAndUnsupportedStoresRemainByteForByteUnchanged() = runBlocking {
        directory.mkdirs()
        val file = File(directory, "v1.json")
        listOf("{broken", "{\"version\":2,\"entries\":[]}", "{\"version\":1,\"entries\":[{}]}").forEach { input ->
            file.writeText(input)
            val before = file.readBytes()
            try { repository.change { emptyList() }; fail("Expected damaged store to reject writes") }
            catch (_: MemoryStorageException) { }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun concurrentRepositoryInstancesDoNotLoseUpdates() = runBlocking {
        (0..11).map { index -> async(Dispatchers.IO) {
            MemoryRepository(directory).change { entries ->
                entries + AgentMemory("item-$index", "Title $index", "Content", "user", 1, 1)
            }
        } }.awaitAll()
        assertEquals(12, repository.load().size)
    }

    @Test fun fullStoreRejectsNewEntryWithoutDroppingExistingData() = runBlocking {
        repository.change { (0 until MemoryPolicy.MAX_ENTRIES).map {
            AgentMemory("item-$it", "Title $it", "Content", "user", 1, 1)
        } }
        val before = File(directory, "v1.json").readBytes()
        try {
            repository.change { it + AgentMemory("overflow", "Overflow", "Content", "user", 1, 1) }
            fail("Expected capacity rejection")
        } catch (failure: MemoryStorageException) { assertEquals(MemoryFailure.Capacity, failure.failure) }
        assertArrayEquals(before, File(directory, "v1.json").readBytes())
    }

    @Test fun querySurvivesSuspendedReloadAndOnlyLatestDebouncedResultsPublish() = runBlocking {
        val controller = MemoryController(repository, scope)
        controller.add("Kotlin", "Kotlin language preference").join()
        controller.add("Rust", "Rust language preference").join()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdingWrite = async(Dispatchers.IO) { repository.change { entries ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            entries
        } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val reload = controller.load()
            assertTrue(controller.state.value.loading)
            controller.search("Rust")
            release.countDown()
            reload.join(); holdingWrite.await()
            withTimeout(10_000) { while (controller.state.value.searching) delay(10) }
            assertEquals("Rust", controller.state.value.query)
            assertEquals(listOf("Rust"), controller.state.value.visible.map { it.title })
            repeat(20) { controller.search(if (it % 2 == 0) "Rust" else "Kotlin") }
            controller.search("Kotlin")
            withTimeout(10_000) { while (controller.state.value.searching) delay(10) }
            assertEquals("Kotlin", controller.state.value.query)
            assertEquals(listOf("Kotlin"), controller.state.value.visible.map { it.title })
            // A stale Default search must not replace the latest result after its debounce.
            delay(200)
            assertEquals(listOf("Kotlin"), controller.state.value.visible.map { it.title })
        } finally { release.countDown(); holdingWrite.join() }
    }

}
