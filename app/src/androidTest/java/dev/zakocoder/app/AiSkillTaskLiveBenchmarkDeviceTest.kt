package dev.zakocoder.app

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly opt-in, paid real-provider TASK experiment. Never copies or changes model settings. */
class AiSkillTaskLiveBenchmarkDeviceTest {
    private data class Usage(val input: Long?, val output: Long?, val cached: Long?, val status: Int?, val complete: Boolean)
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) { while (!condition()) delay(25) }

    @Test(timeout = 600000) fun compareIdenticalRenameTasksWithEagerAndLazySkills() = runBlocking {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments().getString("skill_task_live_benchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Only the main app may use its existing provider in-process", context.packageName == "dev.zakocoder.app")
        val workbench = (context.applicationContext as ZakoApplication).workbench
        fun idle() = !workbench.state.value.loading && !workbench.state.value.busy &&
            !workbench.ai.state.value.active && !workbench.ai.state.value.restoring &&
            !workbench.projectTasks.state.value.busy && !workbench.git.state.value.busy &&
            !workbench.mcp.state.value.running && !workbench.mcp.state.value.busy &&
            !workbench.screenTests.state.value.active && !workbench.screenTests.state.value.busy
        withTimeoutOrNull(15000) { await { !workbench.state.value.loading } }
        assumeTrue("Existing work must be idle; benchmark never stops it", idle())
        val config = AiConfigStore(context)
        assumeTrue("Configure a provider before opting in", config.load().let { it.endpoint.isNotBlank() && it.model.isNotBlank() })
        val proxy = SettingsRepository(context).proxyForRuntime()
        val token = UUID.randomUUID().toString()
        val owned = File(context.cacheDir, "skill-task-$token").apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val records = JSONArray()
        val report = JSONObject().put("kind", "real_controller_skill_task_comparison")
            .put("project_id", PROJECT).put("order", "eager-1,lazy-1,eager-2,lazy-2")
            .put("provider_cache_guaranteed", false)
            .put("caveat", "Real provider usage; unavailable counters remain null. Backend caching, independent session keys, request order and model decisions affect results. Four independent identical public tasks; no user projects or stored settings changed.")
            .put("enabled_skills", JSONArray(AiBuiltinSkills.defaults))
        val deadline = SystemClock.elapsedRealtime() + 570000L
        try {
            for ((index, mode) in listOf("eager", "lazy", "eager", "lazy").withIndex()) {
                // Do not start another paid task if a user has begun real work meanwhile.
                assumeTrue("Existing app work became active", idle())
                val runRoot = File(owned, "run-$index").apply { mkdirs() }
                val project = File(runRoot, "project").apply { mkdirs() }
                File(project, "sample.py").writeText(SOURCE)
                val isolated = object : ContextWrapper(context) {
                    override fun getFilesDir() = File(runRoot, "files").apply { mkdirs() }
                    override fun getNoBackupFilesDir() = File(runRoot, "private").apply { mkdirs() }
                    override fun getCacheDir() = File(runRoot, "cache").apply { mkdirs() }
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                        val isolatedName = "skill-task-$token-$index-$name"
                        synchronized(preferences) { preferences.add(isolatedName) }
                        return context.getSharedPreferences(isolatedName, mode)
                    }
                }
                val commands = AtomicInteger()
                lateinit var ai: AiController
                val usages = linkedMapOf<String, Usage>()
                fun capture(state: AiState) = synchronized(usages) {
                    state.requests.forEach { usages[it.id] = Usage(it.inputTokens, it.outputTokens, it.cachedTokens, it.status, it.complete) }
                }
                main {
                    ai = AiController(isolated, { id -> require(id == PROJECT); project }, { _, _ -> false },
                        { _, _ -> commands.incrementAndGet(); error("Commands forbidden in public fixture benchmark") }, {}, {},
                        transport = ChatTransport(), config = config, proxy = { proxy }, canStartTask = { idle() })
                    ai.select(PROJECT)
                    ai.refreshGlobalSkills()
                }
                val collector = launch(Dispatchers.Main.immediate) { ai.state.collect { capture(it) } }
                val record = JSONObject().put("mode", mode).put("repeat", index / 2 + 1)
                val started = SystemClock.elapsedRealtime()
                try {
                    val remaining = deadline - started
                    require(remaining > 0) { "Benchmark budget exhausted" }
                    withTimeout(minOf(180000L, remaining)) {
                        await { ai.state.value.configured && !ai.state.value.restoring && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && ai.globalSkills.value.skillsInitialized && !ai.globalSkills.value.skillsLoading }
                        val policy = AiPolicy(AiPermission.Deny, ALLOWED.associateWith { AiPermission.Allow })
                        main { ai.setPolicy(policy); ai.recommendedGlobalSkills() }
                        await { !ai.state.value.permissionsSaving && !ai.globalSkills.value.active }
                        main { ai.setGlobalSkillOnDemand(mode == "lazy") }
                        await { !ai.globalSkills.value.active }
                        assertEquals(policy, ai.state.value.policy)
                        assertEquals(AiBuiltinSkills.defaults.sorted(), ai.state.value.enabledSkills.sorted())
                        assertEquals(mode == "lazy", ai.state.value.skillOnDemand)
                        main { assertTrue("Public task should start", ai.send(TASK)) }
                        await { !ai.state.value.active }
                    }
                    record.put("finished", true)
                } catch (_: TimeoutCancellationException) {
                    record.put("finished", false).put("failure", "task_timeout")
                } catch (failure: Exception) {
                    // Exception text can contain network endpoints. Emit only a safe class name.
                    record.put("finished", false).put("failure", failure.javaClass.simpleName)
                } finally {
                    main { if (ai.state.value.active) ai.stop() }
                    withTimeoutOrNull(5000) { await { !ai.state.value.active } }
                    main { capture(ai.state.value) }
                    collector.cancelAndJoin()
                    val state = ai.state.value
                    val events = state.events
                    val succeeded = events.filter { it.status == AiToolStatus.Succeeded }
                    val readIndex = succeeded.indexOfFirst { it.tool == AiTool.ReadFile }
                    val editIndex = succeeded.indexOfFirst { it.tool == AiTool.EditFile }
                    val skillIndex = succeeded.indexOfFirst { it.tool == AiTool.LoadSkill && it.target.contains("investigate-first") }
                    val workflow = readIndex >= 0 && editIndex > readIndex &&
                        succeeded.drop(editIndex + 1).any { it.tool == AiTool.ReadFile } &&
                        (mode == "eager" || skillIndex in 0 until readIndex) &&
                        events.none { it.tool !in ALLOWED && it.tool != AiTool.LoadSkill } &&
                        events.none { it.tool == AiTool.WriteFile }
                    val fileCorrect = File(project, "sample.py").readText() == EXPECTED &&
                        project.walkTopDown().filter { it.isFile }.map { it.relativeTo(project).path }.toList() == listOf("sample.py")
                    val usage = synchronized(usages) { usages.values.toList() }
                    fun counter(name: String, get: (Usage) -> Long?) {
                        val known = usage.mapNotNull(get)
                        record.put(name, if (usage.isNotEmpty() && known.size == usage.size) known.sum() else JSONObject.NULL)
                            .put("${name}_reported_requests", known.size)
                            .put("${name}_reported_subtotal", if (known.isEmpty()) JSONObject.NULL else known.sum())
                    }
                    counter("input_tokens") { it.input }; counter("output_tokens") { it.output }; counter("cache_read_tokens") { it.cached }
                    record.put("requests", JSONArray(usage.map { JSONObject().put("input_tokens", it.input ?: JSONObject.NULL)
                        .put("output_tokens", it.output ?: JSONObject.NULL).put("cache_read_tokens", it.cached ?: JSONObject.NULL)
                        .put("http_status", it.status ?: JSONObject.NULL).put("complete", it.complete) }))
                        .put("request_count", usage.size).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("phase", state.phase.name).put("failure_kind", state.failureKind?.name ?: JSONObject.NULL)
                        .put("load_skill_count", succeeded.count { it.tool == AiTool.LoadSkill })
                        .put("command_executions", commands.get()).put("file_correct", fileCorrect).put("workflow_correct", workflow)
                        .put("accepted", record.optBoolean("finished") && state.phase == AiPhase.Completed && fileCorrect && workflow && commands.get() == 0)
                    records.put(record)
                }
            }
        } finally {
            report.put("tasks", records)
            File(context.cacheDir, "skill-task-live-benchmark.json").writeText(report.toString(2))
            owned.deleteRecursively()
            synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
        }
        assertEquals("Expected four independent real tasks", 4, records.length())
        assertTrue("Some real tasks failed acceptance; inspect the bounded benchmark report", (0 until records.length()).all { records.getJSONObject(it).optBoolean("accepted") })
    }

    private companion object {
        const val PROJECT = "public-skill-rename-benchmark"
        const val SOURCE = "def greet(name):\n    return f\"Hello, {name}!\"\n"
        const val EXPECTED = "def greet_user(name):\n    return f\"Hello, {name}!\"\n"
        const val TASK = "这是公开独立测试工程。先应用 investigate-first，读取 sample.py 作为证据，确认后仅使用 edit_file 将函数 greet 重命名为 greet_user，参数和函数体逐字保持不变。然后用 read_file 核对。不要运行任何命令，不要修改其他文件，不要使用 write_file 重写。无需运行测试，最终简短说明修改及未运行测试。"
        val ALLOWED = setOf(AiTool.ReadFile, AiTool.ListFiles, AiTool.SearchFiles, AiTool.UpdatePlan, AiTool.EditFile, AiTool.WriteFile)
    }
}
