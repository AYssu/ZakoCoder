package dev.zakocoder.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.ChatTransport
import dev.zakocoder.app.projects.Project
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** Exercise actual Workbench refresh against a wholly private, uninstalled runtime. No UI or downloads. */
class DependencyRefreshDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(20) }
    private class Owned(private val actual: Context) : ContextWrapper(actual), AutoCloseable {
        private val token = "dependency-refresh-${UUID.randomUUID()}"
        val directory = File(actual.cacheDir, token).apply { check(mkdir()) }
        private val preferences = CopyOnWriteArraySet<String>()
        val serviceStarts = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$token-$name"
            preferences += owned
            return actual.getSharedPreferences(owned, mode)
        }
        override fun startService(service: Intent): ComponentName? {
            serviceStarts.incrementAndGet()
            error("Dependency detection must not start a service")
        }
        override fun startForegroundService(service: Intent): ComponentName? {
            serviceStarts.incrementAndGet()
            error("Dependency detection must not start a foreground service")
        }
        // Releasing this isolated workbench must not stop the real application service.
        override fun stopService(service: Intent): Boolean = false
        override fun close() {
            preferences.forEach { actual.deleteSharedPreferences(it) }
            check(directory.deleteRecursively())
        }
    }
    private suspend fun ready(w: Workbench) = await {
        !w.state.value.loading && !w.state.value.busy && !w.state.value.checkingPlans &&
            !w.editor.state.value.loading && !w.ai.models.state.value.loading
    }
    private suspend fun refresh(w: Workbench) {
        main { w.refreshDependencies() }
        await { !w.state.value.checkingPlans }
        assertFalse(w.state.value.plansError)
        assertEquals(w.state.value.selectedId, w.state.value.plansProjectId)
    }
    private suspend fun create(w: Workbench, name: String): Project {
        main { w.create(name) }; ready(w)
        assertFalse(w.state.value.message, w.state.value.messageIsError)
        return requireNotNull(w.state.value.project)
    }
    private fun assertNoExecution(w: Workbench, o: Owned, modelCalls: AtomicInteger) {
        assertFalse(w.state.value.installed)
        assertFalse(w.state.value.checkingTools)
        assertEquals(0, w.state.value.toolCheckRevision)
        assertTrue(w.state.value.bundleTools.isEmpty())
        assertFalse(w.state.value.busy)
        assertEquals(0, o.serviceStarts.get())
        assertEquals(0, modelCalls.get())
        assertTrue(w.terminal.sessions.isEmpty())
        assertFalse("No runtime installation or guest preparation", w.runtime.home.exists())
        assertFalse("No runtime download cache", File(o.cacheDir, "runtime-download").exists())
    }
    private fun transport(calls: AtomicInteger) = ChatTransport(OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls.incrementAndGet(); call.cancel() }
        }).build())

    @Test(timeout = 90000) fun rootManifestRefreshWorksWithoutUbuntuAndDoesNotInstallOrContactConfiguredProxy() = runBlocking {
        Owned(base).use { o ->
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { proxy ->
                withContext(Dispatchers.IO) {
                    SettingsRepository(o).save(AppSettings(proxy = "http://127.0.0.1:${proxy.localPort}"))
                }
                val modelCalls = AtomicInteger()
                lateinit var w: Workbench
                main { w = Workbench(o, aiTransport = transport(modelCalls)) }
                try {
                    ready(w)
                    val project = create(w, "manifest-refresh")
                    val root = withContext(Dispatchers.IO) { w.projects.directory(project.id) }
                    assertTrue(w.state.value.plans.isEmpty())
                    withContext(Dispatchers.IO) {
                        File(root, "package.json").writeText("{\"private\":true}")
                        File(root, "requirements.txt").writeText("# No packages installed by this test\n")
                        File(root, "nested").mkdir()
                        File(root, "nested/Cargo.toml").writeText("[package]\nname=\"ignored_nested_fixture\"\n")
                    }
                    refresh(w)
                    assertEquals(listOf("npm install", "python3 -m venv .venv && .venv/bin/python -m pip install -r requirements.txt"), w.state.value.plans.map { it.command })
                    assertTrue(w.state.value.plans.all { it.cwd == "." })
                    assertTrue("Manifests need not update cached project kinds", w.state.value.project!!.kinds.isEmpty())
                    withContext(Dispatchers.IO) {
                        check(File(root, "package.json").delete())
                        check(File(root, "requirements.txt").delete())
                        File(root, "pyproject.toml").writeText("[project]\nname=\"editable_fixture\"\n")
                        File(root, "go.mod").writeText("module fixture.invalid/local\n")
                    }
                    refresh(w)
                    assertEquals(listOf("python3 -m venv .venv && .venv/bin/python -m pip install -e .", "go mod download"), w.state.value.plans.map { it.command })
                    withContext(Dispatchers.IO) {
                        check(File(root, "pyproject.toml").delete())
                        check(File(root, "go.mod").delete())
                    }
                    // Existing doctor entry must refresh file-derived plans even when Ubuntu is absent.
                    main { w.doctor() }; await { !w.state.value.checkingPlans }
                    assertEquals(project.id, w.state.value.plansProjectId)
                    assertTrue(w.state.value.plans.isEmpty())
                    assertFalse(w.state.value.plansError)
                    assertNoExecution(w, o, modelCalls)
                    withContext(Dispatchers.IO) {
                        assertEquals(setOf("nested"), root.listFiles()!!.map { it.name }.toSet())
                        proxy.soTimeout = 150
                        try { proxy.accept().use { fail("Detection contacted the configured download proxy") } }
                        catch (_: SocketTimeoutException) { /* No connection queued during the tested operations. */ }
                    }
                } finally {
                    withContext(NonCancellable) { main { w.cancel() }; ready(w) }
                }
            }
        }
    }

    @Test(timeout = 90000) fun overlappingRefreshesAndProjectSwitchesKeepPlansBoundToSelectedRoot() = runBlocking {
        Owned(base).use { o ->
            val modelCalls = AtomicInteger()
            lateinit var w: Workbench
            main { w = Workbench(o, aiTransport = transport(modelCalls)) }
            var observer: Job? = null
            try {
                ready(w)
                val a = create(w, "npm-root")
                val b = create(w, "cargo-root")
                withContext(Dispatchers.IO) {
                    File(w.projects.directory(a.id), "package.json").writeText("{\"private\":true}")
                    File(w.projects.directory(b.id), "Cargo.toml").writeText("[package]\nname=\"fixture\"\n")
                }
                refresh(w)
                val violations = CopyOnWriteArrayList<String>()
                val expected = mapOf(a.id to listOf("npm install"), b.id to listOf("cargo fetch"))
                observer = launch(Dispatchers.Main.immediate) {
                    w.state.collect { state ->
                        // Same visibility guard as EnvironmentPage: mismatched-owner/loading plans are hidden.
                        if (state.plansProjectId == state.selectedId && !state.checkingPlans && state.selectedId in expected &&
                            state.plans.map { it.command } != expected[state.selectedId]) {
                            violations += "${state.selectedId}:${state.plans.map { it.command }}"
                        }
                    }
                }
                repeat(8) { index ->
                    val target = if (index % 2 == 0) a else b
                    main {
                        // Launch reads of the previous root, then switch while their IO may still be pending.
                        repeat(3) { w.refreshDependencies() }
                        w.select(target.id)
                        w.refreshDependencies()
                    }
                    ready(w)
                    assertEquals(target.id, w.state.value.selectedId)
                    assertEquals(target.id, w.state.value.plansProjectId)
                    assertFalse(w.state.value.plansError)
                    assertEquals(expected[target.id], w.state.value.plans.map { it.command })
                }
                assertTrue("A visible plan must belong to its selected root: $violations", violations.isEmpty())
                assertNoExecution(w, o, modelCalls)
            } finally {
                withContext(NonCancellable) {
                    observer?.cancelAndJoin()
                    main { w.cancel() }; ready(w)
                }
            }
        }
    }
}
