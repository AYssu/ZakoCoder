package dev.zakocoder.app

import android.content.Intent
import android.os.Process
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiFileAttachmentStore
import dev.zakocoder.app.ai.AiHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Run prepare alone, force-stop/relaunch the isolated application, then run restore alone.
 * The two stages intentionally share only a bounded fixture on disk, never JVM state.
 */
class AiFileAttachmentRestartDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = File(context.cacheDir, "attachment-restart-fixture.json")
    private val body get() = "Restart fixture: complete UTF-8 text\n" + "测试😀0123456789\n".repeat(1400) + "independent complete attachment tail\n"

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(message: String, condition: () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(30)
        assertTrue(message, condition())
    }

    @Test(timeout = 90_000)
    fun preparePersistentDraft(): Unit = runBlocking {
        check(context.packageName.endsWith(".runtimecheck")) { "Only the isolated test installation is allowed" }
        check(!fixture.exists()) { "A previous restart fixture must be restored and cleaned before preparing another" }
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await("application initialized") { !workbench.state.value.loading && !workbench.state.value.busy }
        check(!workbench.ai.state.value.active) { "An unrelated task is active" }
        val previousProject = workbench.state.value.selectedId
        val oldProjects = workbench.state.value.projects.map { it.id }.toSet()
        val token = UUID.randomUUID().toString()
        val projectName = "Attachment restart $token"
        val sourceDirectory = File(context.cacheDir, "apk-install/attachment-restart-$token")
        var ownedProject: String? = null
        var prepared = false
        try {
            main { workbench.completeOnboarding() }
            await("onboarding complete") { !workbench.state.value.onboarding.required }
            main { workbench.create(projectName) }
            await("owned workspace created") {
                !workbench.state.value.busy && workbench.state.value.projects.any { it.id !in oldProjects && it.name == projectName }
            }
            val project = workbench.state.value.projects.single { it.id !in oldProjects && it.name == projectName }.id
            ownedProject = project
            main { workbench.ai.select(project) }
            await("owned conversation loaded") {
                workbench.ai.state.value.let { it.projectId == project && !it.permissionsLoading && !it.restoring }
            }
            val baseline = workbench.ai.state.value
            assertFalse(baseline.historyError)
            assertTrue(baseline.references.isEmpty())
            assertTrue(baseline.fileAttachments.isEmpty())
            val source = withContext(Dispatchers.IO) {
                check(sourceDirectory.mkdirs())
                File(sourceDirectory, "restart-source.txt").also { it.writeText(body, Charsets.UTF_8) }
            }
            val sourceUri = FileProvider.getUriForFile(context, context.packageName + ".apk-files", source)
            main {
                assertTrue(workbench.incomingShares.receive(Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_STREAM, sourceUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
            }
            await("share persisted without sending") {
                workbench.ai.state.value.let {
                    !it.imagesLoading && !it.permissionsSaving && it.references.size == 1 && it.fileAttachments.size == 1
                }
            }
            val shared = workbench.ai.state.value
            val attachment = shared.fileAttachments.single()
            assertEquals(baseline.conversationId, shared.conversationId)
            assertEquals(listOf(attachment.id), shared.fileDraftIds)
            assertEquals(attachment, shared.references.single().attachment)
            assertEquals("", shared.references.single().text)
            assertFalse(shared.references.single().render().contains("independent complete attachment tail"))
            assertEquals(baseline.requests, shared.requests)
            assertEquals(baseline.timeline, shared.timeline)
            assertEquals(baseline.turn, shared.turn)
            assertFalse(shared.active)
            val saved = withContext(Dispatchers.IO) {
                requireNotNull(AiHistoryStore(context).load(project, baseline.conversationId).state)
            }
            assertEquals(shared.fileAttachments, saved.fileAttachments)
            assertEquals(shared.fileDraftIds, saved.fileDraftIds)
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), attachment.bytes)
            withContext(Dispatchers.IO) {
                assertEquals(body, source.readText(Charsets.UTF_8))
                assertTrue("Remove the provider source before the process restart", source.delete())
                assertFalse(source.exists())
                val document = JSONObject().put("project", project).put("name", projectName).put("token", token)
                    .put("conversation", baseline.conversationId).put("previousProject", previousProject ?: JSONObject.NULL)
                    .put("attachment", attachment.id).put("sha256", attachment.sha256).put("bytes", attachment.bytes)
                    .put("turn", baseline.turn).put("requests", JSONArray(baseline.requests.map { it.id }))
                    .put("timeline", JSONArray(baseline.timeline.map { it.id })).put("process", Process.myPid())
                fixture.writeText(document.toString(), Charsets.UTF_8)
            }
            prepared = true
        } finally {
            if (!prepared) {
                ownedProject?.let { removeOwnedWorkspace(workbench, it, projectName, previousProject) }
                withContext(Dispatchers.IO) { sourceDirectory.deleteRecursively(); fixture.delete() }
            }
        }
    }

    @Test(timeout = 90_000)
    fun restorePersistentDraftAndCleanup(): Unit = runBlocking {
        check(context.packageName.endsWith(".runtimecheck")) { "Only the isolated test installation is allowed" }
        val document = withContext(Dispatchers.IO) { JSONObject(fixture.readText(Charsets.UTF_8)) }
        val project = UUID.fromString(document.getString("project")).toString()
        val token = UUID.fromString(document.getString("token")).toString()
        val projectName = "Attachment restart $token"
        check(document.getString("name") == projectName)
        val conversation = UUID.fromString(document.getString("conversation")).toString()
        val attachmentId = UUID.fromString(document.getString("attachment")).toString()
        val previousProject = document.optString("previousProject").takeUnless { it.isBlank() || it == "null" }
        val sourceDirectory = File(context.cacheDir, "apk-install/attachment-restart-$token")
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await("fresh application initialized") { !workbench.state.value.loading && !workbench.state.value.busy }
        try {
            assertNotEquals("The restore phase must run after force-stop in a new process", document.getInt("process"), Process.myPid())
            check(workbench.state.value.projects.any { it.id == project && it.name == projectName })
            main {
                assertTrue(workbench.openWorkspace(project))
                workbench.ai.select(project)
            }
            await("draft recovered from disk") {
                workbench.ai.state.value.let { it.projectId == project && !it.restoring && !it.permissionsLoading }
            }
            val restored = workbench.ai.state.value
            assertFalse(restored.historyError)
            assertEquals(conversation, restored.conversationId)
            assertEquals(listOf(attachmentId), restored.fileDraftIds)
            val attachment = restored.fileAttachments.single()
            assertEquals(attachmentId, attachment.id)
            assertEquals(document.getString("sha256"), attachment.sha256)
            assertEquals(document.getLong("bytes"), attachment.bytes)
            assertEquals(attachment, restored.references.single().attachment)
            assertEquals("attachment:$attachmentId", restored.references.single().path)
            assertEquals("", restored.references.single().text)
            assertEquals(document.getInt("turn"), restored.turn)
            assertEquals(document.getJSONArray("requests").toString(), JSONArray(restored.requests.map { it.id }).toString())
            assertEquals(document.getJSONArray("timeline").toString(), JSONArray(restored.timeline.map { it.id }).toString())
            assertFalse(restored.active)
            assertFalse(restored.imagesLoading)
            val source = File(sourceDirectory, "restart-source.txt")
            assertFalse("The original shared file no longer exists", source.exists())
            val sourceUri = FileProvider.getUriForFile(context, context.packageName + ".apk-files", source)
            assertTrue("Source provider access must now fail", withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(sourceUri)?.use { it.read() } ?: error("No source") }.isFailure
            })
            val store = AiFileAttachmentStore(context)
            val complete = StringBuilder()
            var offset = 0
            do {
                val page = store.read(attachment, offset, 1000)
                complete.append(page.getString("content"))
                val next = page.getInt("next_offset")
                check(next > offset || !page.getBoolean("has_more"))
                offset = next
            } while (page.getBoolean("has_more"))
            assertEquals("Private archive preserves the entire original UTF-8 file", body, complete.toString())
            val found = store.search(attachment, "independent complete attachment tail")
            assertEquals(1, found.getJSONArray("matches").length())
            assertTrue(found.getJSONArray("matches").getJSONObject(0).getInt("offset") > 6000)
            assertEquals(document.getJSONArray("requests").toString(), JSONArray(workbench.ai.state.value.requests.map { it.id }).toString())
        } finally {
            removeOwnedWorkspace(workbench, project, projectName, previousProject)
            withContext(Dispatchers.IO) {
                sourceDirectory.deleteRecursively()
                fixture.delete()
            }
        }
    }

    private suspend fun removeOwnedWorkspace(workbench: Workbench, project: String, expectedName: String, previousProject: String?) {
        if (workbench.state.value.projects.any { it.id == project && it.name == expectedName }) {
            await("owned workspace idle before cleanup") { !workbench.state.value.busy && !workbench.ai.state.value.permissionsLoading }
            main { workbench.removeProject(project) }
            await("only owned workspace removed") { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == project } }
        }
        if (previousProject != null && workbench.state.value.projects.any { it.id == previousProject }) {
            await("workspace ready for restoring previous selection") {
                !workbench.state.value.busy && !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.restoring
            }
            main { assertTrue(workbench.openWorkspace(previousProject)) }
            await("previous workspace restored") { !workbench.state.value.busy && workbench.state.value.selectedId == previousProject }
        }
    }
}
