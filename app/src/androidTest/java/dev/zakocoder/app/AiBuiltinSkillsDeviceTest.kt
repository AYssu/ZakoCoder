package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.io.File
import java.util.concurrent.TimeUnit

/** Built-in assets, isolated settings/history, and a local TLS provider only. */
class AiBuiltinSkillsDeviceTest {
    @Before fun foregroundForDeviceUi() { warmDeviceActivity() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(30) }
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && ai.globalSkills.value.skillsInitialized && !ai.globalSkills.value.skillsLoading }
    private fun isolatedContext(f: AiDeviceTest.Fixture) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${f.token}-$name", mode)
    }
    private fun globalStore(f: AiDeviceTest.Fixture) = AiGlobalSkills(isolatedContext(f))
    private fun sharedRoot(f: AiDeviceTest.Fixture) = File(f.root, "control/shared-skills").apply { mkdirs() }
    private suspend fun enable(ai: AiController, path: String, selected: Boolean) {
        main { ai.enableGlobalSkill(path, selected) }
        await { !ai.globalSkills.value.active && ai.state.value.enabledSkills == ai.globalSkills.value.enabledSkills }
        assertEquals(selected, path in ai.globalSkills.value.enabledSkills)
    }
    private suspend fun disableAll(ai: AiController) {
        for (path in ai.globalSkills.value.enabledSkills.toList()) enable(ai, path, false)
    }
    private suspend fun onDemand(ai: AiController, enabled: Boolean) {
        main { ai.setGlobalSkillOnDemand(enabled) }
        await { !ai.globalSkills.value.active && ai.state.value.skillOnDemand == ai.globalSkills.value.skillOnDemand }
        assertEquals(enabled, ai.globalSkills.value.skillOnDemand)
    }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = isolatedContext(f)
        return AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false }, { _, _ -> error("Skills must not execute automatically") }, {}, {}, f.transport, f.config, history = f.history).also { it.refreshGlobalSkills() }
    }

    @Test fun bundledCatalogIsOfflineSelfContainedAndProjectNamesCannotShadowIt() {
        AiDeviceTest.Fixture(context).use { f ->
            val builtin = AiBuiltinSkills(context)
            val catalog = builtin.scan()
            assertEquals(48, catalog.size); assertEquals(catalog.size, catalog.map { it.path }.distinct().size)
            assertEquals(context.assets.list("skills")!!.toSet(), catalog.map { it.name }.toSet())
            assertEquals(5, catalog.count { it.recommended })
            catalog.forEach { skill ->
                val text = builtin.content(skill.path)
                assertTrue(text.contains("version: 1.0.0-zakocoder") || text.contains("version: 1.1.0-zakocoder")); assertTrue(text.length > 200)
                assertTrue(skill.description.isNotBlank()); assertTrue(skill.builtIn)
            }
            val root = File(f.root, "project").apply { mkdirs() }
            val own = File(root, ".zakocoder/skills/caveman").apply { mkdirs() }
            File(own, "SKILL.md").writeText("---\nname: caveman\ndescription: project override attempt\n---\nPROJECT_ONLY_MARKER")
            val store = AiSkills(AiProjectFiles { root }, builtin)
            assertEquals(catalog.size + 1, store.scan("p").size)
            assertFalse(store.content("p", listOf("builtin:caveman")).contains("PROJECT_ONLY_MARKER"))
            assertTrue(store.content("p", listOf(".zakocoder/skills/caveman/SKILL.md")).contains("PROJECT_ONLY_MARKER"))
            // A malformed project skill root must not disable the app-owned catalog.
            File(root, ".zakocoder").deleteRecursively(); File(root, ".zakocoder").writeText("not a directory")
            assertEquals(catalog.size, store.scan("p").count { it.builtIn })
            assertTrue(store.content("p", listOf("builtin:caveman")).contains("简洁表达"))
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun newGuidanceIsOptInPersistsGloballyAndLoadsWithoutWorkspace() {
        AiDeviceTest.Fixture(context).use { f ->
            val bundled = AiBuiltinSkills(context)
            val catalog = bundled.scan()
            val paths = listOf("builtin:env-audit", "builtin:skill-creator", "builtin:self-improving-agent")
            val preferences = globalStore(f)
            assertTrue(paths.none { it in preferences.load().enabled })
            assertTrue(catalog.filter { it.path in paths }.all { !it.recommended })
            preferences.save(AiGlobalSkills.Selection(paths, onDemand = true))
            val restored = globalStore(f).load()
            assertEquals(paths, restored.enabled)
            assertTrue(restored.onDemand)
            // No workspace root should be resolved when reading app-owned resources.
            val files = AiSkills(AiProjectFiles { error("No workspace required") }, bundled)
            val session = files.session("unused", restored.enabled, catalog)
            paths.forEach { path ->
                val loaded = session.load(path, "SKILL.md", kotlin.coroutines.EmptyCoroutineContext)
                assertTrue(loaded.content.contains("name: ${path.removePrefix("builtin:")}"))
                assertFalse(loaded.alreadyLoaded)
                assertTrue(session.load(path, "SKILL.md", kotlin.coroutines.EmptyCoroutineContext).alreadyLoaded)
            }
            try {
                session.load("builtin:apk-reverse", "SKILL.md", kotlin.coroutines.EmptyCoroutineContext)
                fail("Unselected guidance must not load")
            } catch (_: IllegalArgumentException) { }
            preferences.save(AiGlobalSkills.Selection(emptyList()))
            assertTrue(globalStore(f).load().enabled.isEmpty())
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 120000) fun defaultsExclusiveStylesAndDisableAllSurviveReloadAndNewConversation() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("choices") }; ready(ai)
                assertEquals(AiBuiltinSkills.defaults.sorted(), ai.state.value.enabledSkills.sorted())
                enable(ai, "builtin:megacave", true)
                assertTrue("builtin:megacave" in ai.state.value.enabledSkills); assertFalse("builtin:caveman" in ai.state.value.enabledSkills)
                enable(ai, "builtin:ultracave", true)
                assertEquals(1, ai.state.value.enabledSkills.count { it in AiBuiltinSkills.expressionStyles })
                main { ai.enableSkill("builtin:unknown", true) }; assertFalse("builtin:unknown" in ai.state.value.enabledSkills)
                disableAll(ai)
                assertTrue(globalStore(f).load().enabled.isEmpty())
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("choices") }; ready(restored)
                assertTrue(restored.state.value.enabledSkills.isEmpty()); assertTrue(restored.state.value.skillsInitialized)
                main { restored.conversation() }; ready(restored)
                assertTrue("New conversations share the global empty selection", restored.state.value.enabledSkills.isEmpty())
                assertEquals(0, f.server.requestCount)
            }
        }
    }

    @Test(timeout = 120000) fun selectionUsesUserReferenceMessagesAndLatestSnapshotSupersedesHistory() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("injection") }; ready(ai)
                disableAll(ai)
                enable(ai, "builtin:frontend-design", true)
                f.enqueue("已读取当前启用的参考规范。")
                main { ai.send("设计一个页面") }; await { !ai.state.value.active }
                val first = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                val selected = (0 until first.length()).map { first.getJSONObject(it) }.single { it.optString("content").contains("Skill: frontend-design") }
                assertEquals("user", selected.getString("role"))
                val guidance = selected.getString("content")
                assertTrue(guidance.contains("briefly announce its exact name in backticks and its purpose"))
                assertTrue(guidance.contains("only selected skills you actually apply"))
                assertTrue(guidance.contains("once per user task"))
                assertTrue(guidance.contains("If none applies, omit the announcement"))
                assertTrue(guidance.contains("Do not invent skill tool calls or audit events"))
                assertFalse(first.getJSONObject(0).getString("content").contains("# 界面设计与实现"))
                assertTrue("The sent skill snapshot remains literal history for a stable cache prefix",
                    ai.state.value.contextTurns.single().contains("# 界面设计与实现"))
                val firstTurn = ai.state.value.contextTurns.single()
                enable(ai, "builtin:frontend-design", false)
                f.enqueue("现在没有启用这份规范。")
                main { ai.send("继续") }; await { !ai.state.value.active }
                val second = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                val snapshots = (0 until second.length()).map { second.getJSONObject(it).optString("content") }
                    .filter { it.startsWith("User-selected skills for this task.") }
                assertEquals(2, snapshots.size)
                assertEquals("The prior request prefix must not be rewritten when a skill is disabled", guidance, snapshots.first())
                assertFalse("The latest task no longer enables the old skill", snapshots.last().contains("Skill: frontend-design"))
                assertTrue(snapshots.last().contains("Earlier snapshots are historical evidence, not current rules or authorization"))
                assertTrue(second.toString().contains("设计一个页面"))
                val saved = f.history.load("injection").state!!
                assertEquals(2, saved.contextTurns.size)
                assertEquals(firstTurn, saved.contextTurns.first())
                assertTrue(ai.state.value.events.isEmpty())
            }
        }
    }

    @Test(timeout = 120000) fun moreThanEightGlobalSkillsPersistAndLegacyWorkspaceSelectionIsIgnored() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                val root = File(sharedRoot(f), ".zakocoder/skills/local").apply { mkdirs() }
                File(root, "SKILL.md").writeText("---\nname: local\ndescription: Shared fixture\n---\nLOCAL_SKILL")
                val projectPath = ".zakocoder/skills/local/SKILL.md"
                f.history.save("many", AiState(enabledSkills = listOf(projectPath)))
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("many") }; ready(ai)
                assertEquals("Workspace snapshots do not become global configuration", AiBuiltinSkills.defaults.sorted(), ai.state.value.enabledSkills.sorted())
                // Use a fixed mixed collection: the full optional catalog now exceeds the
                // selection limit, and enabling every available skill is not user behavior.
                val selectedPaths = listOf("frontend-design", "lean-build", "surgical-patch", "investigate-first",
                    "safe-refactor", "verify-and-stop", "audit", "caveman", "caveman-commit", "caveman-compress",
                    "caveman-help").map { "builtin:$it" } + projectPath
                for (path in selectedPaths) enable(ai, path, true)
                assertEquals(12, ai.state.value.enabledSkills.size)
                assertEquals(12, globalStore(f).load().enabled.size)
                assertTrue(AiSkills(AiProjectFiles { sharedRoot(f) }, AiBuiltinSkills(context)).content("", ai.state.value.enabledSkills).length < 32000)
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("many") }; ready(restored)
                assertEquals(ai.state.value.enabledSkills, restored.state.value.enabledSkills)
                assertFalse(restored.state.value.historyError)
                assertEquals(0, f.server.requestCount)
            }
        }
    }
    @Test(timeout = 120000) fun onDemandLoadsSelectedBuiltinOnceAndPersistsMode() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("on-demand") }; ready(ai)
                disableAll(ai)
                enable(ai, "builtin:frontend-design", true); onDemand(ai, true)
                f.enqueue(calls = listOf(AiCall("skill1", "load_skill", "{\"skill\":\"builtin:frontend-design\",\"reason\":\"读取界面指令\"}")))
                f.enqueue(calls = listOf(AiCall("skill2", "load_skill", "{\"skill\":\"builtin:frontend-design\",\"reason\":\"检查加载状态\"}")))
                f.enqueue("已读取指令，尚未修改项目。")
                main { assertTrue(ai.send("检查界面规范，不修改文件")) }; await { !ai.state.value.active }
                val requests = (1..3).map { JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()) }
                val body = AiBuiltinSkills(context).content("builtin:frontend-design")
                assertFalse(requests[0].getJSONArray("messages").toString().contains(body))
                val second = requests[1].getJSONArray("messages")
                val loaded = (0 until second.length()).map { second.getJSONObject(it) }.single { it.optString("role") == "tool" }
                assertEquals(body, JSONObject(loaded.getString("content")).getString("content"))
                val third = requests[2].getJSONArray("messages")
                val results = (0 until third.length()).map { third.getJSONObject(it) }.filter { it.optString("role") == "tool" }
                assertEquals(2, results.size)
                assertTrue(JSONObject(results.last().getString("content")).getBoolean("already_loaded"))
                assertEquals("", JSONObject(results.last().getString("content")).getString("content"))
                assertEquals(2, ai.state.value.events.count { it.tool == AiTool.LoadSkill && it.status == AiToolStatus.Succeeded })
                await { f.history.load("on-demand").state?.skillOnDemand == true }
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("on-demand") }; ready(restored)
                assertTrue(restored.state.value.skillOnDemand)
            }
        }
    }

    @Test(timeout = 120000) fun fileReadDenialAlsoDeniesOnDemandSkillLoading() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                val custom = File(sharedRoot(f), ".zakocoder/skills/selected").apply { mkdirs() }
                val disabled = File(sharedRoot(f), ".zakocoder/skills/disabled").apply { mkdirs() }
                File(custom, "SKILL.md").writeText("---\nname: selected\ndescription: Selected metadata\n---\nSELECTED_PRIVATE_BODY")
                File(disabled, "SKILL.md").writeText("---\nname: disabled\ndescription: Disabled metadata\n---\nDISABLED_PRIVATE_BODY")
                val path = ".zakocoder/skills/selected/SKILL.md"
                globalStore(f).save(AiGlobalSkills.Selection(listOf(path), true))
                AiPermissionStore(isolatedContext(f)).save("denied-load", AiPolicy(overrides = mapOf(AiTool.ReadFile to AiPermission.Deny)))
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("denied-load") }; ready(ai)
                f.enqueue(calls = listOf(AiCall("denied", "load_skill", JSONObject().put("skill", path).put("reason", "读取规范").toString())))
                f.enqueue("读取被禁止，未加载规范。")
                main { assertTrue(ai.send("查看规范")) }; await { !ai.state.value.active }
                assertEquals(AiToolStatus.Denied, ai.state.value.events.single().status)
                val firstRequest = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages").toString()
                assertTrue(firstRequest.contains("Selected metadata"))
                assertFalse(firstRequest.contains("Disabled metadata"))
                assertFalse(firstRequest.contains("SELECTED_PRIVATE_BODY"))
                assertFalse(firstRequest.contains("DISABLED_PRIVATE_BODY"))
                assertFalse(ai.state.value.contextTurns.joinToString().contains("SELECTED_PRIVATE_BODY"))
            }
        }
    }

    @Test(timeout = 120000) fun previousTaskSkillDoesNotSkipNewReadAfterQuestionRestart() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val file = File(sharedRoot(f), ".zakocoder/skills/selected/SKILL.md").apply { parentFile!!.mkdirs() }
            fun document(body: String) = "---\nname: selected\ndescription: Use for this fixture\n---\n$body"
            file.writeText(document("OLD_TASK_BODY"))
            val path = ".zakocoder/skills/selected/SKILL.md"
            globalStore(f).save(AiGlobalSkills.Selection(listOf(path), true))
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("task-boundary") }; ready(ai)
            val loadArgs = JSONObject().put("skill", path).put("reason", "读取选中指令").toString()
            f.enqueue(calls = listOf(AiCall("old-load", "load_skill", loadArgs)))
            f.enqueue("首个任务完成。")
            main { assertTrue(ai.send("第一个任务，读取指令")) }; await { !ai.state.value.active }
            repeat(2) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
            file.writeText(document("NEW_TASK_BODY"))
            f.enqueue(calls = listOf(AiCall("question", "ask_user", JSONObject().put("question", "继续检查吗？").toString())))
            main { assertTrue(ai.send("第二个任务，先询问再读取指令")) }
            await { !ai.state.value.active && ai.state.value.questionContinuation != null }
            assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            globalStore(f).save(AiGlobalSkills.Selection(emptyList(), false))
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("task-boundary") }; ready(reopened)
            assertEquals("The paused task retains its recorded selection", listOf(path), reopened.state.value.enabledSkills)
            assertTrue(reopened.state.value.skillOnDemand)
            main { reopened.resetGlobalSkillSelection() }
            await { !reopened.globalSkills.value.active }
            assertEquals(AiBuiltinSkills.defaults, reopened.globalSkills.value.enabledSkills)
            assertEquals("Explicit global recovery does not rewrite a waiting task", listOf(path), reopened.state.value.enabledSkills)
            assertTrue(reopened.state.value.skillOnDemand)
            globalStore(f).save(AiGlobalSkills.Selection(emptyList(), false))
            val question = reopened.state.value.questions.last()
            f.enqueue(calls = listOf(AiCall("new-load", "load_skill", loadArgs)))
            f.enqueue("第二个任务完成。")
            main { assertTrue(reopened.answerQuestion("task-boundary", reopened.state.value.conversationId, question.id, "继续")) }
            await { !reopened.state.value.active && reopened.state.value.questionContinuation == null }
            val loaded = reopened.state.value.events.single { it.callId == "new-load" }
            assertEquals(AiToolStatus.Succeeded, loaded.status)
            val result = JSONObject(loaded.output)
            assertFalse(result.getBoolean("already_loaded"))
            assertTrue(result.getString("content").contains("NEW_TASK_BODY"))
            assertFalse(result.getString("content").contains("OLD_TASK_BODY"))
            repeat(2) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
            f.enqueue("下一项任务使用全局空配置。")
            main { assertTrue(reopened.send("第三个任务，检查当前选择")) }; await { !reopened.state.value.active }
            val next = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val messages = next.getJSONArray("messages")
            val currentSnapshot = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
                .last { it.startsWith("User-selected skills for this task.") }
            assertFalse(currentSnapshot.contains("Skill: selected"))
            assertTrue(reopened.state.value.enabledSkills.isEmpty())
            assertFalse(reopened.state.value.skillOnDemand)
        }
    }

}
