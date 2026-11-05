package dev.zakocoder.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

class AiSkillSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val skill = AiSkill("builtin:chosen", "chosen", "Use for a specific task")
    private fun load(session: AiSkillSession, path: String = skill.path, resource: String = "SKILL.md") = session.load(path, resource, EmptyCoroutineContext)

    @Test fun catalogIncludesOnlySelectionAndNoBody() {
        var reads = 0
        val session = AiSkillSession(listOf(skill)) { _, _ -> reads++; "private body" }
        assertTrue(session.catalog().contains(skill.path))
        assertFalse(session.catalog().contains("private body")); assertEquals(0, reads)
        assertThrows(IllegalArgumentException::class.java) { load(session, "builtin:disabled") }
        assertEquals(0, reads)
    }
    @Test fun oneReadPerResourceAndExplicitInstructionsFirst() {
        var reads = 0
        val session = AiSkillSession(listOf(skill)) { _, resource -> reads++; "body of $resource" }
        assertThrows(IllegalArgumentException::class.java) { load(session, resource = "references/help.md") }
        assertEquals(0, reads)
        assertFalse(load(session).alreadyLoaded)
        assertTrue(load(session).alreadyLoaded)
        assertEquals("", load(session).content)
        assertFalse(load(session, resource = "references/help.md").alreadyLoaded)
        assertEquals(2, reads)
    }
    @Test fun traversalAndSensitiveResourcesNeverRead() {
        var reads = 0
        val session = AiSkillSession(listOf(skill)) { _, _ -> reads++; "body" }
        listOf("../secret", "/etc/passwd", "references/../../secret", "x//y", "x\\y", ".env", "a/private.key", "a/./b", "x\u0000y").forEach {
            assertThrows(IllegalArgumentException::class.java) { load(session, resource = it) }
        }
        assertEquals(0, reads)
    }
    @Test fun customReferencesUseRealProjectPathProtections() {
        val root = temporary.newFolder(); val store = AiSkills(AiProjectFiles { root })
        val selected = store.create("p", "selected", "For testing", "original body")
        val directory = File(root, selected.path).parentFile!!
        File(directory, "references").mkdir()
        File(directory, "references/help.md").writeText("help content")
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        Files.createSymbolicLink(File(directory, "linked.md").toPath(), outside.toPath())
        val session = store.session("p", listOf(selected.path))
        assertTrue(load(session, selected.path).content.contains("original body"))
        assertEquals("help content", load(session, selected.path, "references/help.md").content)
        assertThrows(AiFileFailure::class.java) { load(session, selected.path, "linked.md") }
    }
    @Test fun cancellationBeforeReadAndDuringReadDoesNotPublishContent() {
        val before = Job().also { it.cancel() }; var reads = 0
        val session = AiSkillSession(listOf(skill)) { _, _ -> reads++; "body" }
        assertThrows(CancellationException::class.java) { session.load(skill.path, "SKILL.md", before) }
        assertEquals(0, reads)
        val during = Job()
        val interrupted = AiSkillSession(listOf(skill)) { _, _ -> during.cancel(); "not published" }
        assertThrows(CancellationException::class.java) { interrupted.load(skill.path, "SKILL.md", during) }
        assertFalse(load(interrupted).alreadyLoaded)
    }
    @Test fun aggregateBudgetRejectsWithoutLosingPreviousLoads() {
        val session = AiSkillSession(listOf(skill)) { _, _ -> "x".repeat(25000) }
        load(session)
        assertThrows(IllegalArgumentException::class.java) { load(session, resource = "second.md") }
        assertTrue(load(session).alreadyLoaded)
        assertThrows(IllegalArgumentException::class.java) { load(session, resource = "second.md") }
    }
    @Test fun loadPermissionCannotBypassFileDenialOrAsk() {
        AiPermission.entries.forEach { permission ->
            val policy = AiPolicy(overrides = mapOf(AiTool.ReadFile to permission, AiTool.LoadSkill to AiPermission.Allow))
            assertEquals(permission, policy.permission(AiTool.LoadSkill))
            assertEquals(policy.source(AiTool.ReadFile), policy.source(AiTool.LoadSkill))
        }
    }

}
