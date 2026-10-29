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
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "control").apply { mkdirs() } }
        return AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false }, { _, _ -> error("Skills must not execute automatically") }, {}, {}, f.transport, f.config, history = f.history)
    }

    @Test fun bundledCatalogIsOfflineSelfContainedAndProjectNamesCannotShadowIt() {
        AiDeviceTest.Fixture(context).use { f ->
            val builtin = AiBuiltinSkills(context)
            val catalog = builtin.scan()
            assertEquals(13, catalog.size); assertEquals(13, catalog.map { it.path }.distinct().size)
            assertEquals(5, catalog.count { it.recommended })
            catalog.forEach { skill ->
                val text = builtin.content(skill.path)
                assertTrue(text.contains("version: 1.0.0-zakocoder")); assertTrue(text.length > 200)
                assertTrue(skill.description.isNotBlank()); assertTrue(skill.builtIn)
            }
            val root = File(f.root, "project").apply { mkdirs() }
            val own = File(root, ".zakocoder/skills/caveman").apply { mkdirs() }
            File(own, "SKILL.md").writeText("---\nname: caveman\ndescription: project override attempt\n---\nPROJECT_ONLY_MARKER")
            val store = AiSkills(AiProjectFiles { root }, builtin)
            assertEquals(14, store.scan("p").size)
            assertFalse(store.content("p", listOf("builtin:caveman")).contains("PROJECT_ONLY_MARKER"))
            assertTrue(store.content("p", listOf(".zakocoder/skills/caveman/SKILL.md")).contains("PROJECT_ONLY_MARKER"))
            // A malformed project skill root must not disable the app-owned catalog.
            File(root, ".zakocoder").deleteRecursively(); File(root, ".zakocoder").writeText("not a directory")
            assertEquals(13, store.scan("p").count { it.builtIn })
            assertTrue(store.content("p", listOf("builtin:caveman")).contains("简洁表达"))
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 120000) fun defaultsExclusiveStylesAndDisableAllSurviveReloadAndNewConversation() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("choices") }; ready(ai)
                assertEquals(AiBuiltinSkills.defaults, ai.state.value.enabledSkills)
                main { ai.enableSkill("builtin:megacave", true) }
                assertTrue("builtin:megacave" in ai.state.value.enabledSkills); assertFalse("builtin:caveman" in ai.state.value.enabledSkills)
                main { ai.enableSkill("builtin:ultracave", true) }
                assertEquals(1, ai.state.value.enabledSkills.count { it in AiBuiltinSkills.expressionStyles })
                main { ai.enableSkill("builtin:unknown", true) }; assertFalse("builtin:unknown" in ai.state.value.enabledSkills)
                for (path in ai.state.value.enabledSkills.toList()) main { ai.enableSkill(path, false) }
                await { f.history.load("choices").state?.let { it.skillsInitialized && it.enabledSkills.isEmpty() } == true }
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("choices") }; ready(restored)
                assertTrue(restored.state.value.enabledSkills.isEmpty()); assertTrue(restored.state.value.skillsInitialized)
                main { restored.conversation() }; ready(restored)
                assertEquals(AiBuiltinSkills.defaults, restored.state.value.enabledSkills)
                assertEquals(0, f.server.requestCount)
            }
        }
    }

    @Test(timeout = 120000) fun selectionUsesUserReferenceMessagesAndDisabledBodiesDoNotReturnInContext() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("injection") }; ready(ai)
                for (path in ai.state.value.enabledSkills.toList()) main { ai.enableSkill(path, false) }
                main { ai.enableSkill("builtin:frontend-design", true) }
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
                assertFalse(ai.state.value.contextTurns.single().contains("# 界面设计与实现"))
                main { ai.enableSkill("builtin:frontend-design", false) }
                f.enqueue("现在没有启用这份规范。")
                main { ai.send("继续") }; await { !ai.state.value.active }
                val second = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages").toString()
                assertFalse(second.contains("Skill: frontend-design")); assertTrue(second.contains("设计一个页面"))
                assertEquals(2, f.history.load("injection").state!!.contextTurns.size)
                assertTrue(ai.state.value.events.isEmpty())
            }
        }
    }

    @Test(timeout = 120000) fun moreThanEightSkillsPersistAndLegacySelectionIsPreserved() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                val root = File(f.root, "project/.zakocoder/skills/local").apply { mkdirs() }
                File(root, "SKILL.md").writeText("---\nname: local\n---\nLOCAL_SKILL")
                val projectPath = ".zakocoder/skills/local/SKILL.md"
                f.history.save("many", AiState(enabledSkills = listOf(projectPath)))
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("many") }; ready(ai)
                assertEquals(listOf(projectPath), ai.state.value.enabledSkills)
                for (skill in ai.state.value.skills.filter { it.path !in AiBuiltinSkills.expressionStyles || it.path == "builtin:caveman" }) main { ai.enableSkill(skill.path, true) }
                assertEquals(12, ai.state.value.enabledSkills.size)
                await { f.history.load("many").state?.enabledSkills?.size == 12 }
                assertTrue(AiSkills(AiProjectFiles { File(f.root, "project") }, AiBuiltinSkills(context)).content("many", ai.state.value.enabledSkills).length < 32000)
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("many") }; ready(restored)
                assertEquals(ai.state.value.enabledSkills, restored.state.value.enabledSkills)
                assertFalse(restored.state.value.historyError)
                assertEquals(0, f.server.requestCount)
            }
        }
    }
}
