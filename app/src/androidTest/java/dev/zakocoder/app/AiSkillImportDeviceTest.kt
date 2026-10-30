package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual Android filesystem and controller gates, with isolated state and no network/commands. */
class AiSkillImportDeviceTest {
    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { stream -> entries.forEach { (path, text) ->
            stream.putNextEntry(ZipEntry(path)); stream.write(text.toByteArray()); stream.closeEntry()
        } }
    }.toByteArray()
    private fun document(name: String) = "---\nname: $name\ndescription: device import fixture\n---\n\nRead references/guide.md.\n"

    @Test(timeout = 60000) fun batchImportPreservesAssetsIsIdempotentAndDoesNotEnableOrExecute() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "skills-device-$token").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val project = File(root, "project").apply { mkdirs() }
        var controller: AiController? = null
        try {
            withContext(Dispatchers.Main) {
                controller = AiController(isolated, { check(it == "fixture"); project }, { _, _ -> false },
                    { _, _ -> error("No commands during skill import") }, {}, {}, config = AiConfigStore(isolated, "empty-models", "unused-$token"))
                controller!!.select("fixture")
            }
            val ai = requireNotNull(controller)
            withTimeout(20000) { while (ai.state.value.projectId != "fixture" || ai.state.value.permissionsLoading || ai.state.value.skillsLoading) delay(40) }
            val before = ai.state.value.enabledSkills
            val archive = zip("skills/first/skill.md" to document("first"), "skills/first/references/guide.md" to "guide bytes",
                "skills/second/SkIlL.Md" to document("second"), "skills/second/scripts/run.sh" to "touch should-not-exist\n")
            assertEquals(listOf("first", "second"), ai.inspectSkillArchive(archive))
            val operation = UUID.randomUUID().toString()
            val conversation = ai.state.value.conversationId
            assertTrue(ai.saveProjectSkill("fixture", conversation, "", "", "", true, operation, archive))
            assertTrue(ai.saveProjectSkill("fixture", conversation, "", "", "", true, operation, archive))
            val added = ai.state.value.skills.filter { !it.builtIn }
            assertEquals(2, added.size)
            assertEquals(before, ai.state.value.enabledSkills)
            assertFalse(ai.state.value.restoring)
            assertFalse(File(project, "should-not-exist").exists())
            withContext(Dispatchers.IO) {
                assertEquals("guide bytes", File(File(project, added.first { it.name == "first" }.path).parentFile!!, "references/guide.md").readText())
                val script = File(File(project, added.first { it.name == "second" }.path).parentFile!!, "scripts/run.sh")
                assertEquals("touch should-not-exist\n", script.readText())
                assertFalse(script.canExecute())
                assertEquals(1, File(project, ".zakocoder/skills").listFiles()!!.size)
                val outside = File(root, "outside").apply { mkdirs() }
                val linked = File(root, "linked").apply { mkdirs() }
                File(linked, ".zakocoder").mkdir()
                Files.createSymbolicLink(File(linked, ".zakocoder/skills").toPath(), outside.toPath())
                assertTrue(runCatching { AiSkills(AiProjectFiles { linked }).importArchive("fixture", archive) }.isFailure)
                assertTrue(outside.listFiles()!!.isEmpty())
                assertFalse(project.walkTopDown().any { it.name.startsWith("skill-import-") })
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { controller?.stop() }
            withContext(NonCancellable + Dispatchers.IO) { root.deleteRecursively() }
        }
    }
}
