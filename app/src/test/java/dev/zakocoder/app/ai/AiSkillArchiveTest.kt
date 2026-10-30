package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiSkillArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(name: String) = "---\nname: $name\ndescription: imported instructions\n---\n\nUse references/guide.md.\n"
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { stream -> entries.forEach { (name, content) ->
            stream.putNextEntry(ZipEntry(name)); stream.write(content); stream.closeEntry()
        } }
    }.toByteArray()
    private fun text(path: String, content: String) = path to content.toByteArray()

    @Test fun importsMultipleSkillsWithAssetsAndCaseInsensitiveDocumentsAsOneBatch() {
        val root = temporary.newFolder()
        val skills = AiSkills(AiProjectFiles { root })
        val archive = zip(text("skills/one/skill.md", document("one")), text("skills/one/references/guide.md", "guide"),
            text("skills/two/SkIlL.Md", document("two")), text("skills/two/scripts/run.sh", "echo unsafe"))
        val imported = skills.importArchive("p", archive)
        assertEquals(listOf("one", "two"), imported.map { it.name })
        assertEquals(imported, skills.scan("p"))
        assertTrue(skills.content("p", imported.map { it.path }).contains("Skill: two"))
        val first = File(root, imported[0].path).parentFile!!
        val second = File(root, imported[1].path).parentFile!!
        assertEquals("guide", File(first, "references/guide.md").readText())
        assertEquals("echo unsafe", File(second, "scripts/run.sh").readText())
        assertFalse(File(second, "scripts/run.sh").canExecute())
        assertEquals(1, File(root, ".zakocoder/skills").listFiles()!!.size)
        assertFalse(root.walkTopDown().any { it.name.startsWith("skill-import-") })
    }

    @Test fun importsRootDocumentAndStableRetryDoesNotDuplicateOrOverwrite() {
        val root = temporary.newFolder()
        val store = AiSkills(AiProjectFiles { root })
        val id = java.util.UUID.randomUUID().toString()
        val archive = zip(text("skill.md", "\uFEFF" + document("root")), text("assets/icon.txt", "asset"))
        val first = store.importArchive("p", archive, id)
        assertEquals(first, store.importArchive("p", archive, id))
        assertEquals(1, store.scan("p").size)
        assertTrue(File(root, first.single().path).readText().startsWith("---"))
        assertTrue(runCatching { store.importArchive("p", zip(text("skill.md", document("different"))), id) }.isFailure)
        assertEquals(first, store.scan("p"))
    }

    @Test fun invalidBatchNeverPublishesEvenWhenFirstSkillIsValid() {
        val root = temporary.newFolder()
        val store = AiSkills(AiProjectFiles { root })
        val malformed = zip(text("one/skill.md", document("one")), text("two/SKILL.md", "missing metadata"))
        assertTrue(runCatching { store.importArchive("p", malformed) }.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(runCatching { store.importArchive("p", zip(text("one/skill.md", document("one")), text("one/conflict", "file"), text("one/conflict/nested", "child"))) }.isFailure)
        assertTrue(store.scan("p").isEmpty())
        assertFalse(root.walkTopDown().any { it.name.startsWith("skill-import-") })
    }

    @Test fun rejectsTraversalSensitivePathsAmbiguousSkillsAndExpansionBombs() {
        val cases = listOf(
            zip(text("../skill.md", document("bad"))),
            zip(text("/skill.md", document("bad"))),
            zip(text("one/skill.md", document("one")), text("one/.env", "private")),
            zip(text("one/skill.md", document("one")), text("one/SKILL.md", document("other"))),
            zip(text("skill.md", document("one")), text("child/skill.md", document("two"))),
            zip(text("one/skill.md", document("one")), "one/huge.bin" to ByteArray(AiSkillArchive.MAX_FILE_BYTES + 1))
        )
        cases.forEach { assertTrue(runCatching { AiSkillArchive.parse(it) }.isFailure) }
    }

    @Test fun rejectsUnixSymlinkCentralDirectoryEntryBeforeAnyDiskWrite() {
        val archive = zip(text("one/skill.md", document("one")), text("one/link", "../../external"))
        var index = 0
        while (index < archive.size - 46) {
            if (archive[index] == 0x50.toByte() && archive[index + 1] == 0x4b.toByte() && archive[index + 2] == 1.toByte() && archive[index + 3] == 2.toByte()) {
                // external attributes high word = Unix symlink 0120777.
                archive[index + 40] = 0xff.toByte(); archive[index + 41] = 0xa1.toByte()
                break
            }
            index++
        }
        assertTrue(runCatching { AiSkillArchive.parse(archive) }.isFailure)
    }

    @Test fun rejectsSymlinkedDestinationAndLimitsTotalSkillCountAcrossBatches() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val store = AiSkills(AiProjectFiles { root })
        File(root, ".zakocoder").mkdir()
        Files.createSymbolicLink(File(root, ".zakocoder/skills").toPath(), outside.toPath())
        assertTrue(runCatching { store.importArchive("p", zip(text("skill.md", document("one")))) }.isFailure)
        assertTrue(outside.listFiles()!!.isEmpty())
        File(root, ".zakocoder/skills").delete()
        val entries = (0 until 32).map { text("skill-$it/skill.md", document("skill-$it")) }.toTypedArray()
        assertEquals(32, store.importArchive("p", zip(*entries)).size)
        assertTrue(runCatching { store.importArchive("p", zip(text("skill.md", document("extra")))) }.isFailure)
        assertTrue(runCatching { store.create("p", "extra", "description", "body") }.isFailure)
        assertEquals(32, store.scan("p").size)
    }
}
