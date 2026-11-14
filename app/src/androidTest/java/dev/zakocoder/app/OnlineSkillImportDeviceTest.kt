package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Deterministic GitHub fixtures; uses real ZIP parser and Android skill filesystem, never the public network. */
class OnlineSkillImportDeviceTest {
    private val commit = "1".repeat(40)
    private val tree = "2".repeat(40)
    private val subtree = "3".repeat(40)
    private val document = "---\nname: frontend-helper\ndescription: fixture skill\n---\n\nRead references/guide.md before changing UI.\n".toByteArray()
    private val guide = "Review the existing screen first.\n".toByteArray()
    private val license = "Fixture license: redistribution permitted with this notice.\n".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").apply {
        update("blob ${bytes.size}\u0000".toByteArray()); update(bytes)
    }.digest().joinToString("") { "%02x".format(it) }
    private fun node(path: String, bytes: ByteArray, mode: String = "100644") = JSONObject().put("path", path).put("type", "blob")
        .put("mode", mode).put("sha", sha(bytes)).put("size", bytes.size)

    private fun transport(requests: MutableList<String>, symlink: Boolean = false, truncated: Boolean = false,
        badBlob: Boolean = false): OnlineSkillTransport = OnlineSkillTransport { url, _ ->
        requests += url
        val prefix = "https://api.github.com/repos/example/skills"
        val result: Any = when (url) {
            prefix -> JSONObject().put("default_branch", "main")
            "$prefix/commits/main" -> JSONObject().put("sha", commit).put("commit", JSONObject().put("tree", JSONObject().put("sha", tree)))
            "$prefix/git/trees/$tree" -> JSONObject().put("truncated", false).put("tree", JSONArray().put(
                JSONObject().put("path", "skills").put("type", "tree").put("mode", "040000").put("sha", subtree)))
            "$prefix/git/trees/$subtree?recursive=1" -> JSONObject().put("truncated", truncated).put("tree", JSONArray()
                .put(node("frontend/SKILL.md", document)).put(node("frontend/references/guide.md", guide, if (symlink) "120000" else "100644"))
                .put(node("backend/SKILL.md", document)))
            "$prefix/license?ref=$commit" -> JSONObject().put("path", "LICENSE").put("sha", sha(license)).put("size", license.size)
                .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(license)).put("license", JSONObject().put("spdx_id", "MIT"))
            "https://raw.githubusercontent.com/example/skills/$commit/skills/frontend/SKILL.md" -> if (badBlob) "altered".toByteArray() else document
            "https://raw.githubusercontent.com/example/skills/$commit/skills/frontend/references/guide.md" -> guide
            else -> error("Unexpected request: $url")
        }
        if (result is ByteArray) result else result.toString().toByteArray()
    }

    @Test fun onlySelectedSkillDownloadsAndPinnedLicenseAndOriginArePreserved() = runBlocking {
        val requests = mutableListOf<String>()
        val service = AiOnlineSkills(transport(requests))
        val catalog = service.discover("https://github.com/example/skills/tree/main/skills")
        assertEquals(2, catalog.candidates.size)
        assertEquals(commit, catalog.commit)
        val prepared = service.prepare(catalog, setOf("skills/frontend"))
        val imported = AiSkillArchive.parse(prepared.bytes).single()
        assertEquals(listOf("frontend-helper"), prepared.names)
        assertArrayEquals(guide, imported.files.getValue("references/guide.md"))
        assertArrayEquals(license, imported.files.getValue("UPSTREAM_LICENSE.txt"))
        assertEquals(commit, JSONObject(String(imported.files.getValue("ZAKOCODER_ORIGIN.json"))).getString("commit"))
        assertEquals(2, requests.count { it.startsWith("https://raw.githubusercontent.com/") })
        assertFalse(requests.any { "/backend/" in it })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "online-skills-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store = AiSkills(AiProjectFiles { root })
            val skills = store.importArchive("fixture", prepared.bytes)
            assertEquals("frontend-helper", skills.single().name)
            assertTrue(File(root, skills.single().path.substringBeforeLast('/') + "/UPSTREAM_LICENSE.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun symlinkResourceRejectsSelectionBeforeAnyFileDownload() = runBlocking {
        val requests = mutableListOf<String>()
        val service = AiOnlineSkills(transport(requests, symlink = true))
        val catalog = service.discover("https://github.com/example/skills/tree/main/skills")
        try { service.prepare(catalog, setOf("skills/frontend")); fail("Expected symlink rejection") }
        catch (failure: OnlineSkillException) { assertEquals(OnlineSkillProblem.UnsafeResource, failure.problem) }
        assertFalse(requests.any { it.startsWith("https://raw.githubusercontent.com/") })
    }

    @Test fun truncatedTreeIsNotPresentedAsCompleteCatalog() = runBlocking {
        val requests = mutableListOf<String>()
        try { AiOnlineSkills(transport(requests, truncated = true)).discover("https://github.com/example/skills/tree/main/skills"); fail("Expected truncation rejection") }
        catch (failure: OnlineSkillException) { assertEquals(OnlineSkillProblem.TooLarge, failure.problem) }
    }

    @Test fun modifiedBlobCannotProduceInstallableArchive() = runBlocking {
        val service = AiOnlineSkills(transport(mutableListOf(), badBlob = true))
        val catalog = service.discover("https://github.com/example/skills/tree/main/skills")
        try { service.prepare(catalog, setOf("skills/frontend")); fail("Expected mismatched blob rejection") }
        catch (failure: OnlineSkillException) { assertEquals(OnlineSkillProblem.InvalidRepository, failure.problem) }
    }

    @Test fun cancellationPropagatesAndDoesNotReportAnInstall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val service = AiOnlineSkills(OnlineSkillTransport { _, _ -> started.complete(Unit); awaitCancellation() })
        val job = async { service.discover("https://github.com/example/skills") }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
