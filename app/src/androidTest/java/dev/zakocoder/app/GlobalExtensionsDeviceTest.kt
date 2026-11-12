package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** No workspace, runtime installation, network or model request is required. */
class GlobalExtensionsDeviceTest {
    @Test fun confirmedResetRepairsOnlySelectionAndCannotAlterAnActiveTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AiDeviceTest.Fixture(context).use { fixture ->
            val prefix = "global-extension-reset-${java.util.UUID.randomUUID()}-"
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "control").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
            }
            val shared = File(isolated.filesDir, "shared-skills")
            val document = "---\nname: preserved\ndescription: Shared user instructions\n---\nPreserve this package."
            val custom = AiSkills(AiProjectFiles { shared }).importMarkdown("", document)
            val selection = File(isolated.filesDir, "shared-skills-selection.json").apply { writeText("{corrupt") }
            val controller = withContext(Dispatchers.Main) {
                AiController(isolated, { File(fixture.root, "workspace").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> error("No tools during reset validation") }, {}, {}, fixture.transport, fixture.config,
                    history = fixture.history).also { it.refreshGlobalSkills() }
            }
            suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(30) }
            try {
                await { controller.globalSkills.value.skillError && !controller.globalSkills.value.skillsLoading }
                val identity = controller.globalSkills.value.conversationId
                assertEquals("{corrupt", selection.readText())
                withContext(Dispatchers.Main) { controller.recommendedGlobalSkills() }
                assertEquals("An ordinary edit cannot silently reset corrupted settings", "{corrupt", selection.readText())
                withContext(Dispatchers.Main) { controller.resetGlobalSkillSelection() }
                await { controller.globalSkills.value.skillsInitialized && !controller.globalSkills.value.active && !controller.globalSkills.value.skillError }
                assertEquals(AiBuiltinSkills.defaults, AiGlobalSkills(isolated).load().enabled)
                assertFalse(AiGlobalSkills(isolated).load().onDemand)
                assertEquals(document, File(shared, custom.path).readText())
                assertTrue(controller.globalSkills.value.skills.any { it.path == custom.path && it.valid })
                withContext(Dispatchers.Main) { controller.refreshGlobalSkills() }
                await { !controller.globalSkills.value.skillsLoading }
                assertEquals("Refresh retains UI library identity", identity, controller.globalSkills.value.conversationId)

                withContext(Dispatchers.Main) { controller.enableGlobalSkill(custom.path, true) }
                await { !controller.globalSkills.value.active }
                val enabled = AiGlobalSkills(isolated).load()
                withContext(Dispatchers.Main) { controller.select("reset-task") }
                await { controller.state.value.configured && !controller.state.value.permissionsLoading && !controller.state.value.skillsLoading }
                fixture.enqueue("仅验证配置生命周期。", responseDelayMillis = 2000)
                withContext(Dispatchers.Main) { assertTrue(controller.send("不执行任何工具")) }
                assertNotNull(withContext(Dispatchers.IO) { fixture.server.takeRequest(5, TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    assertTrue(controller.state.value.active)
                    controller.resetGlobalSkillSelection()
                }
                assertEquals("An active task excludes selection reset", enabled, AiGlobalSkills(isolated).load())
                await { !controller.state.value.active }
                assertEquals("", controller.state.value.error)
                assertEquals(document, File(shared, custom.path).readText())
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { controller.stop() }
                isolated.getSharedPreferences("first-run-setup", 0).edit().clear().commit()
            }
        }
    }

    @Test fun importedZipLoadsNestedResourcesAfterRestartAndAcrossWorkspaces() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AiDeviceTest.Fixture(context).use { fixture ->
            val prefix = "global-extension-zip-${java.util.UUID.randomUUID()}-"
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "control").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
            }
            val controllers = mutableListOf<AiController>()
            fun controller() = AiController(isolated,
                { id -> File(fixture.root, "workspaces/$id").apply { mkdirs() } }, { _, _ -> false },
                { _, _ -> error("Skill loading never executes commands") }, {}, {}, fixture.transport, fixture.config,
                history = fixture.history).also { controllers += it }
            suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(30) }
            try {
                val document = "---\nname: nested-guide\ndescription: Fixture shared package\n---\nRead references/deep/guide.md."
                val archive = ByteArrayOutputStream().also { buffer ->
                    ZipOutputStream(buffer).use { zip ->
                        listOf("skills/nested/SKILL.md" to document, "skills/nested/references/deep/guide.md" to "SHARED_NESTED_RESOURCE").forEach { (path, text) ->
                            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                        }
                    }
                }.toByteArray()
                val first = withContext(Dispatchers.Main) { controller().also { it.refreshGlobalSkills() } }
                await { first.globalSkills.value.skillsInitialized && !first.globalSkills.value.skillsLoading }
                assertTrue(withContext(Dispatchers.Main) { first.saveGlobalSkill("", "", "", true, java.util.UUID.randomUUID().toString(), archive) })
                val skill = first.globalSkills.value.skills.single { !it.builtIn }
                withContext(Dispatchers.Main) { first.enableGlobalSkill(skill.path, true) }
                await { !first.globalSkills.value.active }
                withContext(Dispatchers.Main) { first.setGlobalSkillOnDemand(true) }
                await { !first.globalSkills.value.active }
                assertNull(first.state.value.projectId)

                val reloaded = withContext(Dispatchers.Main) { controller().also { it.select("first"); it.refreshGlobalSkills() } }
                await { reloaded.state.value.configured && !reloaded.state.value.permissionsLoading && !reloaded.state.value.skillsLoading && reloaded.globalSkills.value.skillsInitialized }
                fixture.enqueue(calls = listOf(AiCall("instructions", "load_skill", JSONObject().put("skill", skill.path).put("reason", "读取共享规范").toString())))
                fixture.enqueue(calls = listOf(AiCall("reference", "load_skill", JSONObject().put("skill", skill.path).put("resource", "references/deep/guide.md").put("reason", "读取参考文档").toString())))
                fixture.enqueue("共享文档读取完成。")
                withContext(Dispatchers.Main) { assertTrue(reloaded.send("读取共享规范及参考文档")) }
                await { !reloaded.state.value.active }
                assertEquals("", reloaded.state.value.error)
                val reference = reloaded.state.value.events.single { it.callId == "reference" }
                assertEquals(AiToolStatus.Succeeded, reference.status)
                assertEquals("SHARED_NESTED_RESOURCE", JSONObject(reference.output).getString("content"))
                repeat(3) { assertNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS)) }
                withContext(Dispatchers.Main) { reloaded.select("second") }
                await { !reloaded.state.value.permissionsLoading && !reloaded.state.value.skillsLoading }
                assertTrue(skill.path in reloaded.state.value.enabledSkills)
                assertTrue(reloaded.state.value.skillOnDemand)
                assertEquals(3, fixture.server.requestCount)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { controllers.forEach { it.stop() } }
                isolated.getSharedPreferences("first-run-setup", 0).edit().clear().commit()
            }
        }
    }
    @Test fun skillLibraryAndSelectionSurviveReloadWithoutWorkspace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AiDeviceTest.Fixture(context).use { fixture ->
            val prefix = "global-extension-test-${java.util.UUID.randomUUID()}-"
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "control").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
            }
            fun controller() = AiController(isolated, { error("Must not resolve a workspace") }, { _, _ -> false },
                { _, _ -> error("Must not execute tools") }, {}, {}, fixture.transport, fixture.config, history = fixture.history)
            val controllers = mutableListOf<AiController>()
            suspend fun await(condition: () -> Boolean) = withTimeout(15000) { while (!condition()) delay(30) }
            try {
                val first = controller().also { controllers += it }
                withContext(Dispatchers.Main) { first.refreshGlobalSkills() }
                await { !first.globalSkills.value.skillsLoading && first.globalSkills.value.skills.isNotEmpty() }
                assertNull(first.state.value.projectId)
                val operation = java.util.UUID.randomUUID().toString()
                assertTrue(withContext(Dispatchers.Main) { first.saveGlobalSkill("共享规则", "跨工作区使用", "只修改需要修改的文件。", false, operation, null) })
                val custom = first.globalSkills.value.skills.single { !it.builtIn }
                withContext(Dispatchers.Main) { first.enableGlobalSkill(custom.path, true) }
                await { !first.globalSkills.value.active && custom.path in first.globalSkills.value.enabledSkills }
                val reloaded = controller().also { controllers += it }
                withContext(Dispatchers.Main) { reloaded.refreshGlobalSkills() }
                await { !reloaded.globalSkills.value.skillsLoading && custom.path in reloaded.globalSkills.value.enabledSkills }
                assertTrue(reloaded.globalSkills.value.skills.any { it.path == custom.path })
                assertTrue(reloaded.globalSkillText(custom.path).contains("只修改需要修改的文件"))
                assertNull(reloaded.state.value.projectId)
                // Imported ZIP packages live one directory deeper than single SKILL.md files.
                // History stores a task snapshot; restoring it must not reject this valid path.
                val zippedPath = ".zakocoder/skills/bundle-${java.util.UUID.randomUUID()}/skill-0/SKILL.md"
                val history = AiHistoryStore(File(fixture.root, "zip-history"))
                val conversation = java.util.UUID.randomUUID().toString()
                val snapshot = AiState(projectId = "history-workspace", conversationId = conversation,
                    skillsInitialized = true, enabledSkills = listOf(zippedPath))
                withContext(Dispatchers.IO) { history.save("history-workspace", snapshot) }
                val restored = withContext(Dispatchers.IO) { history.load("history-workspace", conversation).state }
                assertNotNull(restored)
                assertEquals(listOf(zippedPath), restored!!.enabledSkills)
                assertEquals(0, fixture.server.requestCount)
            } finally {
                withContext(Dispatchers.Main) { controllers.forEach { it.stop() } }
                isolated.getSharedPreferences("shared-skills", 0).edit().clear().commit()
                isolated.getSharedPreferences("first-run-setup", 0).edit().clear().commit()
            }
        }
    }
}
