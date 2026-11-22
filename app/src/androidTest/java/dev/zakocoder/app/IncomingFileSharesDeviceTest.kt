package dev.zakocoder.app

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.zakocoder.app.ai.AiReference
import dev.zakocoder.app.ai.AiImageStore
import dev.zakocoder.app.ai.AiFileAttachmentStore
import dev.zakocoder.app.ai.AiHistoryStore
import dev.zakocoder.app.sharing.sharedFileUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real exported share receiver and document provider, using only an isolated test installation.
 * Does not configure a model, issue a model request, install tools, or read a user's documents.
 */
class IncomingFileSharesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val authority get() = instrumentation.context.packageName + ".shared-file-fixture"
    private fun uri(name: String) = Uri.parse("content://$authority/$name")
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(message: String, condition: () -> Boolean) = withTimeout(25_000) {
        while (!condition()) delay(30)
        assertTrue(message, condition())
    }

    private fun single(source: Uri) = Intent(Intent.ACTION_SEND)
        .setClass(context, MainActivity::class.java)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, source)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test fun parsesOnlyExplicitContentFileSharesAndDeduplicatesProviderClipData() {
        val first = uri("normal")
        val other = uri("other-document")
        val duplicated = single(first).apply {
            clipData = ClipData.newRawUri("fixture", first).apply { addItem(ClipData.Item(other)) }
        }
        assertEquals(listOf(first, other), sharedFileUris(duplicated))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, other, first))
            clipData = ClipData.newRawUri("fixture", other)
        }
        assertEquals(listOf(first, other), sharedFileUris(multiple))
        assertEquals(listOf(first), sharedFileUris(Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("fixture", first)
        }))
        assertNull(sharedFileUris(Intent(Intent.ACTION_VIEW, first)))
        assertNull(sharedFileUris(Intent(Intent.ACTION_MAIN)))
        assertRejected(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Only shared text"))
        assertRejected(single(Uri.parse("https://example.invalid/shared.txt")))
        assertRejected(single(Uri.parse("file:///sdcard/shared.txt")))
        assertRejected(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList((0..8).map { uri("document-$it") }))
        })
    }

    @Test(timeout = 180_000)
    fun coldAndWarmSharesAttachToCurrentDraftWithoutSendingOrReplacingOtherReferences(): Unit = runBlocking {
        check(context.packageName.endsWith(".runtimecheck")) { "Never run this fixture against a user's installation" }
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await("application initialized") { !workbench.state.value.loading && !workbench.state.value.busy }
        check(!workbench.ai.state.value.active) { "An unrelated test task is still active" }
        val previousProject = workbench.state.value.selectedId
        val oldIds = workbench.state.value.projects.map { it.id }.toSet()
        val previousNight = shell("cmd uimode night").substringAfter(':').trim()
        check(previousNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
        val imageDirectory = File(context.cacheDir, "apk-install/incoming-shares-${UUID.randomUUID()}")
        var ownedProject: String? = null
        try {
            main { workbench.completeOnboarding() }
            await("test initialization skipped") { !workbench.state.value.onboarding.required }
            main { workbench.create("Share integration ${UUID.randomUUID()}") }
            await("owned workspace created") { !workbench.state.value.busy && workbench.state.value.projects.any { it.id !in oldIds } }
            val project = workbench.state.value.projects.single { it.id !in oldIds }.id
            ownedProject = project
            main { workbench.ai.select(project) }
            await("owned conversation ready") {
                workbench.ai.state.value.let { it.projectId == project && !it.permissionsLoading && !it.restoring }
            }
            val owner = workbench.ai.state.value.conversationId
            val keep = AiReference("existing-draft.kt", "Preserve my existing draft reference")
            main { assertTrue(workbench.ai.addReference(keep)) }
            val timeline = workbench.ai.state.value.timeline
            val requests = workbench.ai.state.value.requests
            val turn = workbench.ai.state.value.turn

            // Warm-up only grants foreground launch eligibility. CLEAR_TASK below creates a fresh
            // activity, exercising onCreate rather than replacing the test's UI with a harness.
            withContext(Dispatchers.IO) { shell("cmd uimode night no"); warmDeviceActivity() }
            val warmedActivity = resumedActivity()
            val cold = single(uri("normal")).apply {
                clipData = ClipData.newRawUri("duplicate document", uri("normal"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            // Track the component lifecycle rather than matching Intent.action: the receiver
            // deliberately clears consumed sharing extras before ActivityScenario can observe it.
            main { context.startActivity(cold) }
            var activity = resumedActivity(excluding = warmedActivity)
            try {
                await("cold share attached exactly once") {
                    !workbench.ai.state.value.imagesLoading && workbench.ai.state.value.references.size == 2
                }
                assertEquals(owner, workbench.ai.state.value.conversationId)
                assertEquals(keep, workbench.ai.state.value.references.first())
                val shared = workbench.ai.state.value.references.last()
                assertEquals("attachment", shared.kind)
                assertEquals("source.kt", shared.displayName)
                assertEquals("", shared.text)
                val full = AiFileAttachmentStore(context).read(requireNotNull(shared.attachment))
                assertTrue(full.getString("content").contains("你好"))
                assertFalse(shared.render().contains("fun shared"))
                main { assertNull("Share action consumed after queue admission", activity.intent.action) }
                val beforeRecreate = activity
                main { activity.recreate() }
                activity = resumedActivity(excluding = beforeRecreate)
                delay(400)
                assertEquals("Rotation must not reimport the share", 2, workbench.ai.state.value.references.size)

                val warm = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setClass(context, MainActivity::class.java)
                    .setType("text/plain")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                        arrayListOf(uri("other-document"), uri("utf8")))
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("duplicate document", uri("other-document")) }
                main { activity.startActivity(warm) }
                await("warm share retains two separate same-name documents") {
                    !workbench.ai.state.value.imagesLoading && workbench.ai.state.value.references.size == 4
                }
                val imported = workbench.ai.state.value.references.filter { it.attachment != null }
                assertEquals(3, imported.size)
                assertEquals(3, imported.map { it.path }.distinct().size)
                assertEquals(setOf("source.kt"), imported.map { it.displayName }.toSet())
                assertTrue(imported.all { it.text.isEmpty() && !it.truncated })
                val unicode = workbench.ai.state.value.fileAttachments.maxBy { it.bytes }
                val unicodePage = AiFileAttachmentStore(context).read(unicode)
                assertTrue(unicodePage.getString("content").startsWith("测试😀"))
                assertTrue(unicodePage.getString("content").endsWith("尾部"))
                val saved = withContext(Dispatchers.IO) { AiHistoryStore(context).load(project, owner).state }
                assertEquals(workbench.ai.state.value.fileDraftIds, requireNotNull(saved).fileDraftIds)
                assertEquals(3, saved.fileAttachments.size)
                assertEquals(owner, workbench.ai.state.value.conversationId)
                assertEquals(timeline, workbench.ai.state.value.timeline)
                assertEquals(requests, workbench.ai.state.value.requests)
                assertEquals(turn, workbench.ai.state.value.turn)
                assertFalse(workbench.ai.state.value.active)
                capture("day-shared-files")
                withContext(Dispatchers.IO) { shell("cmd uimode night yes") }
                delay(700)
                activity = resumedActivity()
                capture("night-shared-files")

                // A later invalid file aborts the entire batch, including already-read valid text.
                while (workbench.ai.state.value.references.any { it.attachment != null }) {
                    main { workbench.ai.removeReference(workbench.ai.state.value.references.indexOfLast { it.attachment != null }) }
                    await("attachment draft removal saved") { !workbench.ai.state.value.permissionsSaving }
                }
                assertEquals(listOf(keep), workbench.ai.state.value.references)
                delay(500)
                val rejected = withContext(Dispatchers.Main) {
                    workbench.ai.addSharedFiles(listOf(uri("normal"), uri("missing")), project, owner)
                }
                assertFalse(rejected)
                assertEquals(listOf(keep), workbench.ai.state.value.references)
                assertTrue(workbench.ai.state.value.pendingImages.isEmpty())
                assertFalse(workbench.ai.state.value.imagesLoading)
                assertEquals(context.getString(R.string.share_file_unavailable), workbench.ai.state.value.error)
                assertEquals(timeline, workbench.ai.state.value.timeline)
                assertEquals(requests, workbench.ai.state.value.requests)
                assertEquals(owner, workbench.ai.state.value.conversationId)

                val png = withContext(Dispatchers.IO) {
                    check(imageDirectory.mkdirs())
                    File(imageDirectory, "shared-screen.png").also { file ->
                        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(android.graphics.Color.rgb(36, 99, 235))
                            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally { bitmap.recycle() }
                    }
                }
                val pngUri = FileProvider.getUriForFile(context, context.packageName + ".apk-files", png)
                val mixed = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setClass(context, MainActivity::class.java)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(pngUri, uri("normal")))
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply {
                        clipData = ClipData.newRawUri("mixed fixture", pngUri)
                            .apply { addItem(ClipData.Item(uri("normal"))) }
                    }
                main { activity.startActivity(mixed) }
                await("mixed PNG and text share imported together") {
                    workbench.ai.state.value.let {
                        !it.imagesLoading && it.references.size == 2 && it.pendingImages.size == 1
                    }
                }
                val image = workbench.ai.state.value.pendingImages.single()
                assertEquals("shared-screen.png", image.displayName)
                assertEquals("image/jpeg", image.mimeType)
                assertEquals(16, image.width)
                assertEquals(16, image.height)
                assertEquals(keep, workbench.ai.state.value.references.first())
                assertEquals("", workbench.ai.state.value.references.last().text)
                assertTrue(AiFileAttachmentStore(context).read(requireNotNull(workbench.ai.state.value.references.last().attachment))
                    .getString("content").contains("你好"))
                assertTrue(AiImageStore(context).dataUrl(image).startsWith("data:image/jpeg;base64,"))
                assertTrue("Sharing must never modify the source PNG", png.isFile)

                // The first image in this failing batch is normalized and written before the
                // binary document fails. Rollback must delete only that newly imported image.
                val existingReferences = workbench.ai.state.value.references
                val existingImages = workbench.ai.state.value.pendingImages
                val archive = File(context.noBackupFilesDir, "ai-images-v1")
                val storedBefore = withContext(Dispatchers.IO) { archive.listFiles().orEmpty().map { it.name }.toSet() }
                val mixedRejected = withContext(Dispatchers.Main) {
                    workbench.ai.addSharedFiles(listOf(pngUri, uri("missing")), project, owner)
                }
                assertFalse(mixedRejected)
                assertEquals(existingReferences, workbench.ai.state.value.references)
                assertEquals(existingImages, workbench.ai.state.value.pendingImages)
                assertEquals("A failed mixed batch must not leak a normalized image", storedBefore,
                    withContext(Dispatchers.IO) { archive.listFiles().orEmpty().map { it.name }.toSet() })
                assertTrue(AiImageStore(context).dataUrl(image).startsWith("data:image/jpeg;base64,"))
                assertFalse(workbench.ai.state.value.imagesLoading)
                assertEquals(owner, workbench.ai.state.value.conversationId)
                assertEquals(timeline, workbench.ai.state.value.timeline)
                assertEquals(requests, workbench.ai.state.value.requests)
                assertEquals(turn, workbench.ai.state.value.turn)
                main { workbench.ai.removeImage(image.id) }
                await("owned imported image removed") {
                    workbench.ai.state.value.pendingImages.isEmpty() && !File(archive, image.id + ".jpg").exists()
                }
            } finally {
                main {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().forEach { it.finish() }
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                imageDirectory.deleteRecursively()
                shell("cmd uimode night $previousNight")
            }
            ownedProject?.let { owned ->
                main { workbench.removeProject(owned) }
                await("owned workspace removed") { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == owned } }
            }
            if (previousProject != null && workbench.state.value.projects.any { it.id == previousProject }) {
                main { workbench.openWorkspace(previousProject) }
                await("original test workspace restored") { !workbench.state.value.busy && workbench.state.value.selectedId == previousProject }
            }
        }
    }

    private fun assertRejected(intent: Intent) {
        assertTrue("Unsupported share must be rejected", runCatching { sharedFileUris(intent) }.isFailure)
    }

    private suspend fun resumedActivity(excluding: MainActivity? = null): MainActivity = withTimeout(25_000) {
        var resumed: MainActivity? = null
        while (resumed == null) {
            main {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it !== excluding }
            }
            if (resumed == null) delay(30)
        }
        requireNotNull(resumed)
    }

    private suspend fun capture(name: String) {
        delay(300)
        withContext(Dispatchers.IO) { instrumentation.waitForIdleSync() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "ui-review/incoming-shares").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
