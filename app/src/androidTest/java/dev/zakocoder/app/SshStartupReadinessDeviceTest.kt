package dev.zakocoder.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.ssh.SshController
import dev.zakocoder.app.ssh.SshProfile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Isolated startup storage and a local model fixture; no user configuration or SSH server is accessed. */
class SshStartupReadinessDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) {
        while (!condition()) delay(10)
    }

    @Test(timeout = 120000) fun firstTaskWaitsForStartupAndReceivesTheLoadedSshCapability() = runBlocking<Unit> {
        AiDeviceTest.Fixture(base).use { f ->
            val holdingSelection = AtomicBoolean(false)
            val selectionStarted = CountDownLatch(1)
            val releaseSelection = CountDownLatch(1)
            val isolated = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(f.root, "startup-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "startup-private").apply { mkdirs() }
                override fun getCacheDir() = File(f.root, "startup-cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val preferences = base.getSharedPreferences("${f.token}-$name", mode)
                    if (name != "projects") return preferences
                    return object : SharedPreferences by preferences {
                        override fun getString(key: String?, defValue: String?): String? {
                            if (key == "selected" && holdingSelection.get()) {
                                selectionStarted.countDown()
                                check(releaseSelection.await(30, TimeUnit.SECONDS)) { "Startup fixture was not released" }
                            }
                            return preferences.getString(key, defValue)
                        }
                    }
                }
                override fun startForegroundService(service: Intent): ComponentName? = service.component
                override fun startService(service: Intent): ComponentName? = service.component
                override fun stopService(service: Intent) = false
            }
            var workbench: Workbench? = null
            try {
                val project = withContext(Dispatchers.IO) {
                    val projects = ProjectRepository(isolated, File(isolated.filesDir, "workspaces"))
                    projects.create("SSH startup fixture").also { projects.save(listOf(it)); projects.select(it.id) }
                }
                val seed = SshController(isolated)
                seed.saveProfile(SshProfile(project.id, "127.0.0.1", username = "startup-fixture"))
                withContext(Dispatchers.IO) {
                    File(isolated.filesDir, "capability-packages.json").writeText(JSONObject()
                        .put("version", 2).put("screen_enabled", false).put("browser_enabled", false)
                        .put("projects", JSONObject().put(project.id, JSONArray().put("ssh"))).toString())
                }
                holdingSelection.set(true)
                main {
                    workbench = Workbench(isolated, f.transport, f.config, AiPermissionStore(isolated), f.history)
                    workbench!!.ai.select(project.id)
                }
                val current = requireNotNull(workbench)
                assertTrue(withContext(Dispatchers.IO) { selectionStarted.await(15, TimeUnit.SECONDS) })
                await {
                    val state = current.ai.state.value
                    state.configured && !state.permissionsLoading && !state.permissionsSaving &&
                        !state.skillsLoading && !current.ai.models.state.value.loading
                }
                assertTrue(current.state.value.loading)
                main { assertFalse("A task must not freeze capability identities while startup is loading", current.ai.send("Describe the configured tools")) }
                assertEquals("A rejected startup send must not contact the model", 0, f.server.requestCount)
                releaseSelection.countDown()
                await { !current.state.value.loading }
                assertTrue(current.capabilityPackages.state.value.loaded)
                assertTrue(current.capabilityPackages.enabled(project.id, CapabilityPackage.Ssh))
                assertNotNull("Startup readiness includes the current SSH configuration identity", current.ssh.configurationLease(project.id))
                f.enqueue("The configured SSH capability is available.")
                main { assertTrue(current.ai.send("Describe the configured tools")) }
                await { !current.ai.state.value.active }
                assertEquals("", current.ai.state.value.error)
                val body = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                val tools = body.getJSONArray("tools")
                assertTrue((0 until tools.length()).any { tools.getJSONObject(it).getJSONObject("function").getString("name") == "ssh_remote" })
                assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("ssh_remote"))
                assertEquals(1, f.server.requestCount)
            } finally {
                releaseSelection.countDown()
                main { workbench?.stopAll() }
                await { workbench?.ai?.state?.value?.active != true }
            }
        }
    }
}
