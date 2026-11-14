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
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real isolated Android files and selection persistence; no user data, guest process or model. */
class AiSkillDeleteDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: () -> Boolean) = withTimeout(20_000) { while (!check()) delay(20) }
    private fun document(name: String) = "---\nname: $name\ndescription: isolated deletion fixture\n---\n\nRead the project before editing.\n"
    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { stream -> entries.forEach { (path, text) ->
            stream.putNextEntry(ZipEntry(path)); stream.write(text.toByteArray()); stream.closeEntry()
        } }
    }.toByteArray()
    private inner class Fixture {
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "skill-delete-$token").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name", mode)
        }
        val shared get() = File(isolated.filesDir, "shared-skills")
        lateinit var ai: AiController
        suspend fun ready() {
            main {
                ai = AiController(isolated, { error("Global skill deletion does not require a workspace") }, { _, _ -> false },
                    { _, _ -> error("No commands expected") }, {}, {}, config = AiConfigStore(isolated, "unused", "unused-$token"))
                ai.refreshGlobalSkills()
            }
            await { ai.globalSkills.value.skillsInitialized && !ai.globalSkills.value.skillsLoading }
        }
        suspend fun add(name: String, imported: Boolean = false): AiSkill {
            val operation = UUID.randomUUID().toString()
            assertTrue(withContext(Dispatchers.Main) { ai.saveGlobalSkill(name, "custom deletion fixture", if (imported) document(name) else "Review existing code.",
                imported, operation, null) })
            return ai.globalSkills.value.skills.single { it.name == name }
        }
        suspend fun remove(path: String) = withContext(Dispatchers.Main) { ai.deleteGlobalSkill(path) }
        suspend fun close() {
            withContext(NonCancellable + Dispatchers.Main) { if (::ai.isInitialized) ai.stop() }
            withContext(NonCancellable + Dispatchers.IO) {
                root.deleteRecursively()
                isolated.getSharedPreferences("first-run-setup", 0).edit().clear().commit()
            }
        }
    }

    @Test(timeout = 60_000) fun createdAndMarkdownImportedSkillsDeleteResourcesAndPersistOnlyTheirDisablement() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val created = f.add("created")
            val imported = f.add("imported", imported = true)
            val keep = f.add("retained")
            val directory = File(f.shared, created.path).parentFile!!
            File(directory, "references").mkdirs()
            File(directory, "references/guide.md").writeText("owned resource")
            main { f.ai.enableGlobalSkill(created.path, true) }
            await { !f.ai.globalSkills.value.active && created.path in f.ai.globalSkills.value.enabledSkills }
            val enabledBefore = f.ai.globalSkills.value.enabledSkills
            assertTrue(f.remove(created.path))
            assertFalse(directory.exists())
            assertEquals(enabledBefore - created.path, f.ai.globalSkills.value.enabledSkills)
            assertEquals(enabledBefore - created.path, AiGlobalSkills(f.isolated).load().enabled)
            assertTrue(f.remove(imported.path))
            assertFalse(File(f.shared, imported.path).parentFile!!.exists())
            assertTrue(File(f.shared, keep.path).isFile)
            assertTrue(f.ai.globalSkills.value.skills.any { it.path == keep.path })
            assertFalse(f.ai.globalSkills.value.active)
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun deletingOneArchiveSkillPreservesSiblingResourcesAndDoesNotResurrectOnSameImportId() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val archive = zip("first/SKILL.md" to document("first"), "first/references/guide.md" to "first resource",
                "second/SKILL.md" to document("second"), "second/scripts/check.sh" to "second resource")
            val operation = UUID.randomUUID().toString()
            assertTrue(withContext(Dispatchers.Main) { f.ai.saveGlobalSkill("", "", "", true, operation, archive) })
            val first = f.ai.globalSkills.value.skills.single { it.name == "first" }
            val second = f.ai.globalSkills.value.skills.single { it.name == "second" }
            assertTrue(f.remove(first.path))
            assertFalse(File(f.shared, first.path).parentFile!!.exists())
            assertEquals("second resource", File(File(f.shared, second.path).parentFile!!, "scripts/check.sh").readText())
            assertTrue(File(f.shared, ".zakocoder/skills/bundle-$operation/.import-sha256").isFile)
            assertFalse("A partially deleted bundle cannot masquerade as a complete idempotent import",
                withContext(Dispatchers.Main) { f.ai.saveGlobalSkill("", "", "", true, operation, archive) })
            assertFalse(File(f.shared, first.path).exists())
            assertTrue(File(f.shared, second.path).exists())
            assertTrue(f.ai.globalSkills.value.skills.any { it.path == second.path })
            assertTrue(f.remove(second.path))
            assertTrue(f.ai.globalSkills.value.skills.none { !it.builtIn })
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun builtInsAndEscapePathsAreRejectedAndNestedLinksNeverDeleteExternalTargets() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val skill = f.add("links")
            val outside = File(f.root, "outside").apply { mkdirs() }
            val sentinel = File(outside, "keep.txt").apply { writeText("external content") }
            val dir = File(f.shared, skill.path).parentFile!!
            Files.createSymbolicLink(File(dir, "external").toPath(), outside.toPath())
            assertFalse(f.remove(AiBuiltinSkills.defaults.first()))
            assertFalse(f.remove(".zakocoder/skills/../../outside/SKILL.md"))
            assertFalse(f.remove(".zakocoder/skills/SKILL.md"))
            val linked = File(f.shared, ".zakocoder/skills/link")
            Files.createSymbolicLink(linked.toPath(), outside.toPath())
            assertFalse(f.remove(".zakocoder/skills/link/SKILL.md"))
            assertEquals("external content", sentinel.readText())
            assertTrue(f.remove(skill.path))
            assertFalse(Files.exists(dir.toPath(), NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(linked.toPath()))
            assertEquals("external content", sentinel.readText())
            Files.delete(linked.toPath())
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun corruptSelectionBlocksDeletionWithoutOverwritingOrRemovingAnySkill() = runBlocking {
        val f = Fixture()
        try {
            f.ready()
            val skill = f.add("protected")
            val selection = File(f.isolated.filesDir, "shared-skills-selection.json")
            main { f.ai.enableGlobalSkill(skill.path, true) }
            await { !f.ai.globalSkills.value.active && skill.path in f.ai.globalSkills.value.enabledSkills }
            val persistedBefore = selection.readText()
            val blockedWrite = File(selection.path + ".new").apply { check(mkdir()) }
            File(blockedWrite, "block-write").writeText("AtomicFile cannot replace this directory")
            assertFalse("Selection save must succeed before removing source files", f.remove(skill.path))
            assertTrue(File(f.shared, skill.path).isFile)
            assertEquals(persistedBefore, selection.readText())
            assertTrue(skill.path in f.ai.globalSkills.value.enabledSkills)
            blockedWrite.deleteRecursively()
            selection.writeText("broken selection fixture")
            assertFalse(f.remove(skill.path))
            assertTrue(File(f.shared, skill.path).isFile)
            assertEquals("broken selection fixture", selection.readText())
            assertTrue(f.ai.globalSkills.value.skillError)
            assertFalse(f.ai.globalSkills.value.active)
            assertFalse(f.remove(skill.path))
            AiGlobalSkills(f.isolated).save(AiGlobalSkills.Selection(emptyList()))
            main { f.ai.refreshGlobalSkills() }
            await { !f.ai.globalSkills.value.skillsLoading && !f.ai.globalSkills.value.skillError }
            assertTrue(f.remove(skill.path))
        } finally { f.close() }
    }
}
