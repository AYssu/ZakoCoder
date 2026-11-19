package dev.zakocoder.app.device

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.R
import dev.zakocoder.app.screentest.ScreenTestController
import dev.zakocoder.app.screentest.ScreenTestException
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Task authorization plus the real Root display; never obtains instrumentation UiAutomation. */
class VirtualScreenTaskDeviceTest {
    private class Fixture(private val production: Context) : AutoCloseable {
        private val token = "virtual-task-${UUID.randomUUID()}"
        // Keep the isolated evidence within the real provider's configured root. A cacheDir
        // filesDir substitute would fail FileProvider without testing the production URI path.
        val root = File(production.filesDir, "screen-test/evidence/$token").apply { check(mkdirs()) }
        private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(production) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                production.getSharedPreferences("$token-$name".also { preferences.add(it) }, mode)
        }
        init {
            check(context.getSharedPreferences("device_access", Context.MODE_PRIVATE).edit()
                .putString("mode", "root").commit())
        }
        val access = DeviceAccessController(context)
        val virtual = VirtualScreenController(context, access)
        val tasks = ScreenTestController(context,
            { File(root, "project-$it").apply { mkdirs() } }, virtual, access)
        override fun close() {
            tasks.stop()
            virtual.close()
            access.stop()
            preferences.toList().forEach(production::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(100)
    }
    private suspend fun awaitDisplayClosed(context: Context, id: Int) {
        val displays = context.getSystemService(DisplayManager::class.java)
        await { displays.getDisplay(id) == null }
    }
    private fun action(name: String) = JSONObject().put("action", name)
    private suspend fun observe(f: Fixture, project: String, lease: String) =
        JSONObject(f.tasks.execute(project, action("observe"), expectedSession = lease).output)
    private fun texts(observation: JSONObject): List<String> = observation.getJSONArray("nodes").let { nodes ->
        (0 until nodes.length()).map { nodes.getJSONObject(it).optString("text") }
    }

    @Test(timeout = 180_000) fun taskLeaseControlsRealDisplayScreenshotSharingFinishAndRootRevocation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val production = instrumentation.targetContext
        Fixture(production).use { f ->
            try {
                withTimeout(10_000) { f.access.state.first { it.loaded } }
                assertEquals(DeviceMode.Root, f.access.state.value.mode)
                assertFalse("Persisted selection alone must not restore a grant", f.access.state.value.rootGranted)
                assertTrue("This device-only integration case requires real Root", f.access.ensureRoot())
                val project = "task-fixture"
                val lease = f.tasks.authorizeDeviceTask(project, "task-first")
                val id = requireNotNull(f.virtual.state.value.displayId)
                assertTrue(id > 0)
                assertEquals(lease, f.tasks.taskLease(project))
                assertTrue(f.tasks.state.value.active)
                assertFalse(f.tasks.state.value.shareScreenshots)
                val launched = JSONObject(f.tasks.execute(project, action("launch")
                    .put("package", instrumentation.context.packageName), expectedSession = lease).output)
                assertTrue(launched.getBoolean("verified"))
                assertEquals(id, launched.getInt("display_id"))
                var observation = observe(f, project, lease)
                assertEquals(id, observation.getInt("display_id"))
                assertTrue(texts(observation).contains("Virtual screen fixture"))
                assertTrue(texts(observation).contains("Count: 0"))

                val noSharing = runCatching {
                    f.tasks.execute(project, action("screenshot"), expectedSession = lease)
                }.exceptionOrNull()
                assertTrue("Screenshots require a separate explicit sharing grant", noSharing is ScreenTestException)
                assertEquals(production.getString(R.string.virtual_screen_sharing_required), noSharing?.message)
                assertFalse(f.tasks.state.value.shareScreenshots)

                val wrongSharingLease = runCatching {
                    f.tasks.setScreenshotSharing(project, true, expectedLease = "expired-$lease")
                }.exceptionOrNull()
                assertTrue(wrongSharingLease is IllegalStateException)
                assertFalse(f.tasks.state.value.shareScreenshots)
                f.tasks.setScreenshotSharing(project, true, expectedLease = lease)
                val captured = f.tasks.execute(project, action("screenshot"), expectedSession = lease)
                assertTrue(JSONObject(captured.output).getBoolean("captured"))
                assertEquals(id, JSONObject(captured.output).getInt("display_id"))
                val uri = requireNotNull(captured.imageUri)
                assertEquals("content", uri.scheme)
                assertEquals("${production.packageName}.apk-files", uri.authority)
                val png = withContext(Dispatchers.IO) {
                    requireNotNull(production.contentResolver.openInputStream(uri)).use { it.readBytes() }
                }
                assertTrue(png.size > 8)
                assertTrue(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                    .contentEquals(png.copyOf(8)))
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
                try {
                    assertEquals(f.virtual.state.value.width, bitmap.width)
                    assertEquals(f.virtual.state.value.height, bitmap.height)
                } finally { bitmap.recycle() }

                observation = observe(f, project, lease)
                val increment = observation.getJSONArray("nodes").let { nodes ->
                    (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == "Increment" }
                }
                val wrongActionLease = runCatching {
                    f.tasks.execute(project, action("click").put("snapshot", observation.getString("snapshot"))
                        .put("node", increment.getString("id")), expectedSession = "expired-$lease")
                }.exceptionOrNull()
                assertTrue(wrongActionLease is ScreenTestException)
                assertTrue("Rejected lease must not inject a click", texts(observe(f, project, lease)).contains("Count: 0"))
                f.tasks.finishTask(project, "expired-$lease")
                assertEquals("An old finish must not revoke a live task", lease, f.tasks.taskLease(project))

                f.tasks.finishTask(project, lease)
                await { !f.tasks.state.value.active && !f.virtual.state.value.active }
                awaitDisplayClosed(production, id)
                assertNull(f.tasks.taskLease(project))
                assertFalse(f.tasks.state.value.shareScreenshots)
                assertTrue(runCatching { observe(f, project, lease) }.isFailure)
                assertTrue(runCatching { f.tasks.setScreenshotSharing(project, true, expectedLease = lease) }.isFailure)

                val nextLease = f.tasks.authorizeDeviceTask(project, "task-after-finish")
                assertNotEquals(lease, nextLease)
                val nextId = requireNotNull(f.virtual.state.value.displayId)
                assertTrue(nextId > 0)
                assertFalse("A new task never inherits screenshot consent", f.tasks.state.value.shareScreenshots)
                f.tasks.execute(project, action("launch").put("package", instrumentation.context.packageName), nextLease)
                assertTrue(texts(observe(f, project, nextLease)).contains("Virtual screen fixture"))
                assertTrue(runCatching { observe(f, project, lease) }.isFailure)
                val generation = f.access.generation
                f.access.useBasic()
                assertTrue(f.access.generation > generation)
                await { !f.tasks.state.value.active && !f.virtual.state.value.active }
                awaitDisplayClosed(production, nextId)
                assertNull(f.tasks.taskLease(project))
                assertTrue(runCatching { observe(f, project, nextLease) }.isFailure)
                assertFalse(f.access.ensureRoot())
                assertTrue(runCatching { f.tasks.authorizeDeviceTask(project, "must-not-reopen-in-basic") }.isFailure)
                assertFalse(f.virtual.state.value.active)
            } finally {
                f.tasks.stop()
                f.virtual.stop()
                // Drain the controller-owned report chain before deleting only this fixture's files.
                runCatching { f.tasks.readReport("task-fixture") }
            }
        }
    }
}
