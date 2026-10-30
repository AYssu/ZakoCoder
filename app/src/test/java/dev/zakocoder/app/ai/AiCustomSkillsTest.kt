package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AiCustomSkillsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createDiscoverAndExplicitlyLoadProjectSkillWithoutOverwriting() {
        val root = temporary.newFolder("project")
        val skills = AiSkills(AiProjectFiles { root })
        val first = skills.create("project", "精简 \"表达\"", "仅在总结时使用", "先说结果，再说证据。")
        val second = skills.create("project", "精简 \"表达\"", "另一份", "保留全部技术事实。")
        assertNotEquals(first.path, second.path)
        assertEquals(2, skills.scan("project").size)
        assertEquals(first.name, skills.scan("project").single { it.path == first.path }.name)
        assertTrue(skills.content("project", listOf(first.path)).contains("先说结果，再说证据。"))
        assertFalse(first.builtIn)
        assertTrue(File(root, first.path).readText().contains("先说结果，再说证据。"))
    }

    @Test fun retryingStableSaveAfterRecreationReturnsSameDirectoryAndNeverOverwrites() {
        val root = temporary.newFolder("project")
        val operation = java.util.UUID.randomUUID().toString()
        val skills = AiSkills(AiProjectFiles { root })
        val first = skills.create("project", "rotation-safe", "description", "body", operation)
        val recreated = AiSkills(AiProjectFiles { root })
        assertEquals(first, recreated.create("project", "rotation-safe", "description", "body", operation))
        assertEquals(1, recreated.scan("project").size)
        assertTrue(runCatching { recreated.create("project", "changed", "description", "different", operation) }.isFailure)
        assertEquals(1, recreated.scan("project").size)
        assertTrue(File(root, first.path).readText().contains("body"))
    }

    @Test fun importingPreservesAllMetadataAndDoesNotExecuteAnything() {
        val root = temporary.newFolder("project")
        val skills = AiSkills(AiProjectFiles { root })
        val markdown = "---\nname: inspect-first\ndescription: inspect changes\nlicense: Apache-2.0\n---\n\nRead references/checklist.md first.\n"
        val imported = skills.importMarkdown("project", markdown)
        assertEquals(markdown, File(root, imported.path).readText())
        assertEquals("inspect-first", imported.name)
        assertEquals(listOf(imported.path), skills.scan("project").map { it.path })
        assertEquals(1, File(root, imported.path).parentFile!!.listFiles()!!.size)
    }

    @Test fun malformedAndOversizedImportsLeaveProjectUntouched() {
        val root = temporary.newFolder("project")
        val skills = AiSkills(AiProjectFiles { root })
        listOf("no metadata", "---\nname: example\ndescription: inspect\nmissing closing header", "---\nname: example\ndescription: inspect\n---\n", "---\nname: example\ndescription: inspect\n---\n" + "a".repeat(AiSkills.MAX_CUSTOM_CHARS)).forEach { content ->
            assertTrue(runCatching { skills.importMarkdown("project", content) }.isFailure)
        }
        assertFalse(File(root, ".zakocoder").exists())
    }

    @Test fun rejectsSymlinkedSkillRootWithoutTouchingExternalDirectory() {
        val root = temporary.newFolder("project")
        val external = temporary.newFolder("external")
        File(root, ".zakocoder").mkdir()
        Files.createSymbolicLink(File(root, ".zakocoder/skills").toPath(), external.toPath())
        val skills = AiSkills(AiProjectFiles { root })
        assertTrue(runCatching { skills.create("project", "safe-name", "description", "body") }.isFailure)
        assertTrue(external.listFiles()!!.isEmpty())
    }

    @Test fun refusesMoreThanThirtyTwoProjectSkillDirectories() {
        val root = temporary.newFolder("project")
        val directory = File(root, ".zakocoder/skills").apply { mkdirs() }
        repeat(32) { File(directory, "existing-$it").mkdir() }
        assertTrue(runCatching { AiSkills(AiProjectFiles { root }).create("project", "new", "description", "body") }.isFailure)
        assertEquals(32, directory.listFiles()!!.size)
    }
}
