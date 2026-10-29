package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.*
import dev.zakocoder.app.git.*
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** All files, history and draft stores belong to a unique private fixture. No user configuration is read. */
class ProjectWorkflowDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(30) }
    private class Fixture(context: android.content.Context) : AutoCloseable {
        val base = File(context.cacheDir, "workflow-test-${UUID.randomUUID()}").apply { mkdirs() }
        val project = File(base, "project").apply { mkdirs() }
        val wrapper = object : ContextWrapper(context) {
            override fun getFilesDir() = File(base, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(base, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("workflow-test-${base.name}-$name", mode)
        }
        override fun close() { base.deleteRecursively() }
    }
    @Test(timeout = 60000) fun projectBoundReferencesRejectRacingSwitchAndAllowReplacingAtCapacity() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "project").apply { mkdirs() }
            val wrapper = object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "reference-control").apply { mkdirs() } }
            lateinit var ai: AiController
            main {
                ai = AiController(wrapper, { root }, { _, _ -> false }, { _, _ -> error("No command expected") }, {}, {}, f.transport, f.config, history = f.history)
                ai.select("project-a")
                assertTrue(ai.state.value.permissionsLoading)
                assertFalse("Rejected send never consumes caller input", ai.send("not yet ready"))
                assertFalse(ai.referenceProject("project-b", AiReference("b.kt", "B_ONLY")))
                assertEquals("project-a", ai.state.value.projectId)
                assertTrue(ai.state.value.references.isEmpty())
            }
            await { !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
            main { assertTrue(ai.referenceProject("project-b", AiReference("b.kt", "B_ONLY"))) }
            await { !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
            assertEquals("project-b", ai.state.value.projectId)
            assertEquals("B_ONLY", ai.state.value.references.single().text)
            main {
                repeat(3) { assertTrue(ai.addReference(AiReference("extra-$it.kt", "extra"))) }
                assertTrue(ai.addReference(AiReference("b.kt", "B_UPDATED")))
                assertEquals(4, ai.state.value.references.size)
                assertEquals("B_UPDATED", ai.state.value.references.single { it.path == "b.kt" }.text)
                assertFalse(ai.addReference(AiReference("fifth.kt", "not added")))
            }
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test(timeout = 60000) fun scopedRulesReferencesAndFreshSnapshots() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            File(f.project, "AGENTS.md").writeText("ROOT_RULE")
            File(f.project, "AGENT.md").writeText("SHOULD_NOT_LOAD")
            File(f.project, "src").mkdir(); File(f.project, "src/AGENTS.md").writeText("NESTED_RULE")
            File(f.project, "other").mkdir(); File(f.project, "other/AGENTS.md").writeText("OUT_OF_SCOPE")
            File(f.project, "src/code.kt").writeText("fun main() = Unit\n".repeat(500))
            val ctx = AiTaskContext { f.project }
            val rules = ctx.rules("p", listOf("src/code.kt"))
            assertEquals(listOf("AGENTS.md", "src/AGENTS.md"), rules.sources)
            assertFalse(rules.text.contains("SHOULD_NOT_LOAD")); assertFalse(rules.text.contains("OUT_OF_SCOPE"))
            val snapshot = ctx.reference("p", "src/code.kt"); assertEquals(6000, snapshot.text.length); assertTrue(snapshot.truncated)
            File(f.project, "src/code.kt").writeText("changed")
            assertFalse(snapshot.text.contains("changed")); assertEquals("changed", ctx.reference("p", "src/code.kt").text)
            File(f.project, "AGENTS.md").writeText("UPDATED_RULE"); assertTrue(ctx.rules("p", emptyList()).text.contains("UPDATED_RULE"))
            assertTrue(runCatching { ctx.reference("p", "../outside") }.isFailure)
            File(f.project, ".env").writeText("fixture-secret")
            assertTrue(runCatching { ctx.reference("p", ".env") }.isFailure)
            Files.createSymbolicLink(File(f.project, "link.kt").toPath(), File(f.project, "src/code.kt").toPath())
            assertTrue(runCatching { ctx.reference("p", "link.kt") }.isFailure)
            assertTrue(AiReference("src/code.kt", "selection", "selection", 10, true).render().contains("unsaved=true"))
        } }
    }
    @Test(timeout = 60000) fun interruptedSnapshotReviewFreezesCurrentFilesAndRestorationSurvivesRetention() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.txt").apply { writeText("before") }
            val b = File(f.project, "b.txt").apply { writeText("before b") }
            val store = File(f.base, "checkpoints")
            val first = AiCheckpoints(store) { f.project }
            val key = first.begin("p", "conversation-a", 1)
            a.writeText("AI change before process death"); b.delete(); File(f.project, "new.txt").writeText("new")
            // No update/finally occurred; reconstruct the store as after a process restart.
            val restarted = AiCheckpoints(store) { f.project }
            assertFalse(restarted.list("p").single().sealed)
            assertTrue(runCatching { restarted.restore("p", key) { false } }.isFailure)
            val reviewed = restarted.reviewInterrupted("p", key)
            assertEquals(setOf("a.txt", "b.txt", "new.txt"), reviewed.changes.map { it.path }.toSet())
            assertEquals(0, restarted.list("p").single().changed) // Reviewing never adopts current bytes as AI output.
            a.writeText("later user edit")
            assertTrue(runCatching { restarted.restore("p", key, reviewed) { false } }.isFailure)
            assertEquals("later user edit", a.readText()); assertFalse(b.exists())
            assertTrue(restarted.workspaceRestorations("p").isEmpty()) // Preflight failed before writes.
            val refreshed = restarted.reviewInterrupted("p", key)
            assertEquals(3, restarted.restore("p", key, refreshed) { false })
            assertEquals("before", a.readText()); assertEquals("before b", b.readText()); assertFalse(File(f.project, "new.txt").exists())
            val fact = restarted.workspaceRestorations("p").single()
            assertEquals("completed", fact.status); assertEquals(3, fact.confirmed); assertEquals("conversation-a", fact.conversation)
            assertTrue(fact.invalidates(fact.started - 1)); assertFalse(fact.invalidates(fact.finished + 1))
            repeat(6) { restarted.begin("p", "conversation-b", it + 2) }
            assertFalse(restarted.list("p").any { it.id == key })
            val again = AiCheckpoints(store) { f.project }
            assertEquals(fact, again.workspaceRestorations("p").single())
            assertTrue(again.workspaceContext("p").contains("conversation-a"))
        } }
    }
    @Test(timeout = 60000) fun durableFileIntentRecoversRenameBeforeManifestProgressWindow() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.txt").apply { writeText("before a") }
            val b = File(f.project, "b.txt").apply { writeText("before b") }
            val store = File(f.base, "checkpoints")
            val cp = AiCheckpoints(store) { f.project }
            val key = cp.begin("p", "source", 1)
            a.writeText("after a"); b.writeText("after b"); cp.update("p", key, true)
            val projectStore = store.listFiles()!!.single()
            val manifest = org.json.JSONObject(File(projectStore, "$key/manifest.json").readText())
            val entry = manifest.getJSONArray("entries").getJSONObject(0)
            assertEquals("a.txt", entry.getString("path"))
            val journal = AiWorkspaceHistory(projectStore)
            val event = journal.begin(key, "source", 1, 2, 0).copy(pendingPath = "a.txt", pendingBefore = entry.opt("before").toString(), pendingAfter = entry.opt("after").toString())
            journal.save(event)
            // Exact durable state if rename succeeded and process died before manifest progress.
            a.writeText("before a")
            val restarted = AiCheckpoints(store) { f.project }
            assertEquals(1, restarted.restore("p", key) { false })
            assertEquals("before a", a.readText()); assertEquals("before b", b.readText())
            assertEquals(2, restarted.workspaceRestorations("p").first().confirmed)
            assertEquals("completed", restarted.workspaceRestorations("p").first().status)
        } }
    }
    @Test(timeout = 60000) fun unsealedFileIntentIsReconciledBeforeReviewWithoutLosingTotals() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.txt").apply { writeText("before a") }
            val b = File(f.project, "b.txt").apply { writeText("before b") }
            val store = File(f.base, "checkpoints")
            val cp = AiCheckpoints(store) { f.project }
            val key = cp.begin("p", "source", 1)
            a.writeText("after a"); b.writeText("after b"); cp.update("p", key, false)
            val projectStore = store.listFiles()!!.single()
            val manifest = org.json.JSONObject(File(projectStore, "$key/manifest.json").readText())
            val entry = manifest.getJSONArray("entries").getJSONObject(0)
            assertEquals("a.txt", entry.getString("path"))
            val journal = AiWorkspaceHistory(projectStore)
            val event = journal.begin(key, "source", 1, 2, 0).copy(pendingPath = "a.txt", pendingBefore = entry.opt("before").toString(), pendingAfter = entry.opt("after").toString())
            journal.save(event)
            // Exact durable state if rename succeeded and process died before manifest progress.
            a.writeText("before a")
            val restarted = AiCheckpoints(store) { f.project }
            val reviewed = restarted.reviewInterrupted("p", key)
            assertEquals(1, restarted.list("p").single().restoredCount)
            assertEquals(1, restarted.restore("p", key, reviewed) { false })
            assertEquals("before a", a.readText()); assertEquals("before b", b.readText())
            assertEquals(2, restarted.workspaceRestorations("p").first().confirmed)
            assertEquals("completed", restarted.workspaceRestorations("p").first().status)
        } }
    }
    @Test(timeout = 60000) fun lastInterruptedRestorationCanFinishMetadataWithoutWritingFiles() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.txt").apply { writeText("original") }
            val store = File(f.base, "checkpoints"); val cp = AiCheckpoints(store) { f.project }
            val key = cp.begin("p", "source", 1)
            a.writeText("after"); cp.update("p", key, false)
            val projectStore = store.listFiles()!!.single()
            val manifest = org.json.JSONObject(File(projectStore, "$key/manifest.json").readText())
            val entry = manifest.getJSONArray("entries").getJSONObject(0)
            val journal = AiWorkspaceHistory(projectStore)
            journal.save(journal.begin(key, "source", 1, 1, 0).copy(pendingPath = "a.txt", pendingBefore = entry.opt("before").toString(), pendingAfter = entry.opt("after").toString()))
            a.writeText("original")
            val reviewed = cp.reviewInterrupted("p", key)
            assertTrue(reviewed.changes.isEmpty()); assertEquals(1, reviewed.restoredCount)
            assertEquals(0, cp.restore("p", key, reviewed) { false })
            assertTrue(cp.list("p").single().restored)
            assertEquals("completed", cp.workspaceRestorations("p").first().status)
            assertEquals(1, cp.workspaceRestorations("p").first().confirmed)
            assertEquals("original", a.readText())
        } }
    }
    @Test(timeout = 60000) fun repeatedInterruptedReviewsBoundBlobStorageAndRejectSupersededVersions() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.txt").apply { writeText("original") }
            val store = File(f.base, "checkpoints")
            val cp = AiCheckpoints(store) { f.project }; val key = cp.begin("p", "source", 1)
            a.writeText("old reviewed")
            val old = cp.reviewInterrupted("p", key)
            repeat(10) { version ->
                a.writeText("large fixture $version\n".repeat(12000))
                cp.reviewInterrupted("p", key)
                val blobs = store.listFiles()!!.single().resolve(key).listFiles()!!.filter { Regex("[a-f0-9]{64}").matches(it.name) }
                assertEquals(2, blobs.size)
                assertTrue(blobs.sumOf { it.length() } < 300000)
            }
            a.writeText("old reviewed") // Hash matches old token, but its private reviewed version has been superseded.
            assertTrue(runCatching { cp.restore("p", key, old) { false } }.isFailure)
            assertEquals("old reviewed", a.readText())
            assertEquals(1, cp.restore("p", key, cp.reviewInterrupted("p", key)) { false })
            assertEquals("original", a.readText())
        } }
    }
    @Test(timeout = 60000) fun partialRestorationRecordsProgressAndRejectsLaterUserChanges() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.txt").apply { writeText("before a") }
            val b = File(f.project, "b.txt").apply { writeText("before b") }
            val cp = AiCheckpoints(File(f.base, "checkpoints")) { f.project }
            val key = cp.begin("p", "source", 7)
            a.writeText("after a"); b.writeText("after b"); cp.update("p", key, true)
            var bVisits = 0
            assertTrue(runCatching { cp.restore("p", key) { path -> path == "b.txt" && ++bVisits == 2 } }.isFailure)
            assertEquals("before a", a.readText()); assertEquals("after b", b.readText())
            val event = cp.workspaceRestorations("p").first()
            assertEquals("partial", event.status); assertEquals(1, event.confirmed); assertEquals(listOf("a.txt"), event.paths)
            assertEquals(1, cp.list("p").single().restoredCount)
            b.writeText("user edit")
            assertTrue(runCatching { cp.restore("p", key) { false } }.isFailure)
            assertEquals("user edit", b.readText())
            b.writeText("after b")
            assertEquals(1, cp.restore("p", key) { false })
            assertEquals(2, cp.workspaceRestorations("p").first().confirmed)
            assertEquals("completed", cp.workspaceRestorations("p").first().status)
        } }
    }
    @Test(timeout = 60000) fun checkpointsSurviveRestartRestoreBytesModesAndRejectAllConflicts() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val a = File(f.project, "a.sh").apply { writeText("#!/bin/sh\necho before\n"); setExecutable(true) }
            val b = File(f.project, "b.txt").apply { writeText("before b") }
            File(f.project, ".env").writeText("fixture-secret")
            val store = File(f.base, "checkpoints")
            val cp = AiCheckpoints(store) { f.project }
            val key = cp.begin("p", UUID.randomUUID().toString(), 1)
            a.writeText("after a"); a.setExecutable(false); b.delete(); File(f.project, "new.txt").writeText("created")
            cp.update("p", key, true)
            val restart = AiCheckpoints(store) { f.project }
            assertEquals(3, restart.list("p").single().changed); assertTrue(restart.list("p").single().limited)
            assertEquals(setOf("a.sh", "b.txt", "new.txt"), restart.changes("p", key).map { it.path }.toSet())
            a.writeText("user edit")
            assertTrue(runCatching { restart.restore("p", key) { false } }.isFailure)
            assertEquals("user edit", a.readText()); assertFalse(b.exists()); assertTrue(File(f.project, "new.txt").exists())
            a.writeText("after a")
            assertTrue(runCatching { restart.restore("p", key) { it == "b.txt" } }.isFailure)
            assertEquals("after a", a.readText()); assertFalse(b.exists())
            assertEquals(3, restart.restore("p", key) { false })
            assertEquals("#!/bin/sh\necho before\n", a.readText()); assertTrue(a.canExecute()); assertEquals("before b", b.readText())
            assertFalse(File(f.project, "new.txt").exists()); assertEquals("fixture-secret", File(f.project, ".env").readText())
            assertTrue(restart.list("p").single().restored)
            assertTrue(runCatching { restart.restore("p", key) { false } }.isFailure)
        } }
    }
    @Test(timeout = 60000) fun checkpointBoundAlsoAppliesAcrossSuccessiveFileBatches() = runBlocking {
        Fixture(context).use { f -> withContext(Dispatchers.IO) {
            val cp = AiCheckpoints(File(f.base, "checkpoints")) { f.project }
            val key = cp.begin("p", "fixture", 1)
            repeat(300) { File(f.project, "first-$it.txt").writeText("first") }
            cp.update("p", key)
            f.project.listFiles()!!.forEach { it.delete() }
            repeat(300) { File(f.project, "second-$it.txt").writeText("second") }
            cp.update("p", key, true)
            val json = org.json.JSONObject(File(File(File(f.base, "checkpoints").listFiles()!!.single(), key), "manifest.json").readText())
            assertEquals(500, json.getJSONArray("entries").length())
            assertTrue(cp.list("p").single().limited)
            assertEquals(200, cp.changes("p", key).size)
            cp.restore("p", key) { false }
            // The unrecorded tail is explicitly limited, never silently deleted by restore.
            assertEquals(100, f.project.listFiles()!!.size)
        } }
    }
    @Test(timeout = 60000) fun logReadersSeeCompletePublishedSnapshotsWhileWritersReplaceThem() = runBlocking {
        Fixture(context).use { f ->
            val old = "old 完整记录\n".repeat(30000)
            val newer = "new 新的记录\n".repeat(30000)
            val tasks = ProjectTasks(f.wrapper, { f.project }, { _, _, _, output -> output(old); CommandResult(0, "") }, { true }, {})
            val result = tasks.observed("p", "fixture log", 5000, {})
            val run = result.runId!!
            val file = withContext(Dispatchers.IO) { File(f.base, "private/project-tasks-v1").walkTopDown().single { it.name == "$run.log" } }
            assertEquals(old, tasks.log("p", run))
            withContext(Dispatchers.IO) {
                // An incomplete same-directory stage must not be visible through the published log path.
                val stage = File(file.parentFile, ".test-staged-log")
                java.io.FileOutputStream(stage).use { out ->
                    val bytes = newer.toByteArray()
                    out.write(bytes, 0, bytes.size / 2); out.flush()
                    assertEquals(old, tasks.log("p", run))
                    out.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
                }
                java.nio.file.Files.move(stage.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            assertEquals(newer, tasks.log("p", run))
            val firstRead = CompletableDeferred<Unit>()
            val publisher = launch(Dispatchers.IO) {
                firstRead.await()
                repeat(30) { index -> dev.zakocoder.app.runtime.PrivateFiles.writeConfined(file.parentFile!!, file.name, if (index % 2 == 0) old else newer) }
            }
            var reads = 0
            do {
                val text = tasks.log("p", run)
                assertTrue("Readers must see one complete UTF-8 snapshot", text == old || text == newer)
                reads++; firstRead.complete(Unit)
            } while (publisher.isActive)
            publisher.join(); assertTrue(reads >= 2)
        }
    }

    @Test(timeout = 60000) fun burstLogsFlushDuringSilenceAndRemainSealedAfterCompletionOrCancellation() = runBlocking {
        Fixture(context).use { f ->
            for (cancel in listOf(false, true)) {
                val emitted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val burst = "BURST_HEAD\n" + "bounded log line\n".repeat(40000) + "BURST_SILENT_TAIL\n"
                val tasks = ProjectTasks(f.wrapper, { f.project }, { _, _, _, output ->
                    output(burst); emitted.complete(Unit)
                    release.await()
                    output("BURST_FINAL_TAIL\n")
                    dev.zakocoder.app.runtime.CommandResult(1, "actual fixture exit")
                }, { true }, {})
                try {
                    main { tasks.select("p") }; await { !tasks.state.value.loading }
                    main { tasks.run(ProjectCommand("test", "fixture burst")) }
                    withTimeout(5000) { emitted.await() }
                    val run = tasks.state.value.runs.first()
                    val live = withContext(Dispatchers.IO) {
                        withTimeout(5000) {
                            var value: String
                            do { value = tasks.log("p", run.id); if (!value.contains("BURST_SILENT_TAIL")) delay(50) } while (!value.contains("BURST_SILENT_TAIL"))
                            value
                        }
                    }
                    assertTrue(tasks.state.value.busy); assertTrue(live.startsWith("BURST_HEAD\n"))
                    assertTrue(live.endsWith("BURST_SILENT_TAIL\n")); assertTrue(live.length in 512000..513000)
                    if (cancel) main { tasks.stop() } else release.complete(Unit)
                    await { !tasks.state.value.busy }
                    assertEquals(if (cancel) "cancelled" else "failed", tasks.state.value.runs.first().status)
                    val sealed = tasks.log("p", run.id)
                    assertTrue(sealed.endsWith(if (cancel) "BURST_SILENT_TAIL\n" else "BURST_FINAL_TAIL\n"))
                    delay(1200)
                    assertEquals("A retired flusher must not overwrite the final log", sealed, tasks.log("p", run.id))
                } finally {
                    withContext(NonCancellable) { main { tasks.stop() }; await { !tasks.state.value.busy } }
                }
            }
        }
    }

    @Test(timeout = 60000) fun commandEvidencePersistsExactClassificationFailureTimeoutAndCancellation() = runBlocking {
        Fixture(context).use { f ->
            val seenTimeout = java.util.concurrent.atomic.AtomicLong()
            val sleeping = CompletableDeferred<Unit>()
            lateinit var tasks: ProjectTasks
            main { tasks = ProjectTasks(f.wrapper, { f.project }, { _, script, timeout, output ->
                seenTimeout.set(timeout); withContext(Dispatchers.IO) { output("live-log\n" + "x".repeat(520000)) }
                if (script == "sleep forever") { sleeping.complete(Unit); awaitCancellation() }
                if (script == "timeout") withTimeout(10) { delay(100) }
                CommandResult(if (script.contains("fail")) 7 else 0, "actual")
            }, { true }, {}); tasks.select("p") }
            await { !tasks.state.value.loading }
            val build = ProjectCommand("build", "build exact", timeoutSeconds = 3600)
            main { tasks.save(build) }; await { tasks.state.value.commands.size == 1 }
            val result = tasks.observed("p", build.shell(), 3600000, {}, "conversation", 2, "ai")
            assertEquals(0, result.exitCode); assertEquals(3600000, seenTimeout.get())
            tasks.observed("p", "echo success", 120000, {}, "conversation", 2, "ai")
            val combined = build.shell() + " && echo success"
            tasks.observed("p", combined, 120000, {}, "conversation", 2, "ai")
            tasks.observed("p", "fail", 120000, {})
            try { tasks.observed("p", "timeout", 1000, {}); fail("Timeout must propagate with its retained run ID") }
            catch (error: ProjectCommandFailure) { assertTrue(error.timedOut); UUID.fromString(error.runId) }
            val job = launch { tasks.observed("p", "sleep forever", 86400000, {}) }; sleeping.await(); job.cancelAndJoin()
            val records = withContext(Dispatchers.IO) { tasks.runs("p") }
            assertEquals("passed", records.single { it.kind == "build" }.status)
            assertEquals("command", records.single { it.script == "echo success" }.kind)
            assertEquals("command", records.single { it.script == combined }.kind)
            assertEquals(7, records.single { it.script == "fail" }.exit)
            assertEquals("timeout", records.single { it.script == "timeout" }.status)
            assertEquals("cancelled", records.single { it.script == "sleep forever" }.status)
            assertTrue(records.all { it.truncated })
            val retained = tasks.log("p", records.first().id)
            assertTrue(retained.length in 512000..512256); assertTrue(retained.startsWith("live-log\n")); assertTrue(retained.contains("middle omitted"))
            main { tasks.select("p") }; await { !tasks.state.value.loading }
            assertEquals(build, tasks.state.value.commands.single()); assertEquals(6, tasks.state.value.runs.size)
            assertTrue(withContext(Dispatchers.IO) { tasks.modelContext("p") }.contains("timeout_seconds=3600"))
            main { tasks.save(ProjectCommand("test", "test", "../outside")) }; await { tasks.state.value.error.isNotEmpty() }
            assertEquals(1, withContext(Dispatchers.IO) { tasks.commands("p") }.size)
        }
    }
    @Test(timeout = 60000) fun cachedEditorDetectsExternalChangesWithoutReplacingBufferOrSaveBaseline() = runBlocking {
        Fixture(context).use { f ->
            val repo = ProjectRepository(f.wrapper, File(f.base, "workspaces").apply { mkdirs() })
            val project = withContext(Dispatchers.IO) { repo.create("owned external change fixture") }
            val a = File(repo.directory(project.id), "a.kt")
            withContext(Dispatchers.IO) { a.writeText("before a\nsecond line"); File(repo.directory(project.id), "b.kt").writeText("before b") }
            lateinit var editor: EditorController
            main { editor = EditorController(f.wrapper, repo, {}) }; await { !editor.state.value.loading }
            main { editor.open(project.id, "a.kt") }; await { !editor.state.value.loading }
            val original = editor.state.value.document!!
            main { original.line = 1; original.column = 3; editor.open(project.id, "b.kt") }; await { !editor.state.value.loading }
            withContext(Dispatchers.IO) { a.writeText("external a") }
            main { editor.open(project.id, "a.kt") }; await { !editor.state.value.diskChecking }
            assertSame(original, editor.state.value.document); assertEquals("before a\nsecond line", original.content.toString())
            assertEquals(1, original.line); assertEquals(3, original.column); assertFalse(editor.state.value.dirty)
            assertEquals(EditorDiskState.Changed, editor.state.value.diskState); assertEquals("external a", editor.state.value.diskVersion!!.text)
            assertEquals("before a\nsecond line", original.baseline.text)
            main { original.content.replace(0, original.content.length, "local draft"); editor.changed(); editor.checkDisk() }; await { !editor.state.value.diskChecking }
            assertTrue(editor.state.value.dirty); assertTrue(editor.state.value.canUndo); assertEquals("local draft", original.content.toString())
            delay(700)
            lateinit var restored: EditorController
            main { restored = EditorController(f.wrapper, repo, {}) }; await { !restored.state.value.loading }
            main { restored.open(project.id, "a.kt") }; await { !restored.state.value.diskChecking }
            assertEquals("local draft", restored.state.value.document!!.content.toString()); assertEquals(EditorDiskState.Changed, restored.state.value.diskState)
            main { editor.save() }; await { !editor.state.value.saving && !editor.state.value.diskChecking }
            assertTrue(editor.state.value.conflict); assertEquals("external a", withContext(Dispatchers.IO) { a.readText() })
            assertEquals("local draft", original.content.toString())
            withContext(Dispatchers.IO) { a.delete() }; main { editor.checkDisk() }; await { !editor.state.value.diskChecking }
            assertEquals(EditorDiskState.Unavailable, editor.state.value.diskState); assertNull(editor.state.value.diskVersion); assertSame(original, editor.state.value.document)
            withContext(Dispatchers.IO) { a.writeBytes(byteArrayOf(0, 1, 2)) }; main { editor.checkDisk() }; await { !editor.state.value.diskChecking }
            assertEquals(EditorDiskState.Unavailable, editor.state.value.diskState)
            withContext(Dispatchers.IO) { a.delete(); Files.createSymbolicLink(a.toPath(), File(a.parentFile, "b.kt").toPath()) }
            main { editor.checkDisk() }; await { !editor.state.value.diskChecking }; assertEquals(EditorDiskState.Unavailable, editor.state.value.diskState)
            withContext(Dispatchers.IO) { a.delete(); a.writeText("latest disk") }; main { editor.reload() }; await { !editor.state.value.loading }
            assertEquals("latest disk", editor.state.value.document!!.content.toString()); assertFalse(editor.state.value.dirty); assertFalse(editor.state.value.conflict)
        }
    }
    @Test(timeout = 60000) fun delayedEditorProbeCannotPublishIntoAnotherTabOrAfterNewSave() = runBlocking {
        Fixture(context).use { f ->
            val repo = ProjectRepository(f.wrapper, File(f.base, "workspaces").apply { mkdirs() })
            val project = withContext(Dispatchers.IO) { repo.create("owned editor race fixture") }
            withContext(Dispatchers.IO) { File(repo.directory(project.id), "a.kt").writeText("a"); File(repo.directory(project.id), "b.kt").writeText("b") }
            val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
            lateinit var editor: EditorController
            main { editor = EditorController(f.wrapper, repo, {}, inspectDisk = { baseline ->
                val value = EditorFileStore(repo::directory).open(baseline.projectId, baseline.path)
                if (first) { first = false; ready.complete(Unit); withContext(NonCancellable) { release.await() } }
                value
            }) }; await { !editor.state.value.loading }
            main { editor.open(project.id, "a.kt") }; await { !editor.state.value.loading }
            lateinit var old: Job
            main { old = editor.checkDisk()!! }; ready.await()
            main { editor.open(project.id, "b.kt") }; await { !editor.state.value.loading }
            val current = editor.state.value.document!!
            main { current.content.replace(0, 1, "saved b"); editor.changed(); editor.save() }
            await { !editor.state.value.saving && !editor.state.value.diskChecking }
            release.complete(Unit); old.join()
            assertEquals("b.kt", editor.state.value.path); assertSame(current, editor.state.value.document)
            assertEquals("saved b", current.baseline.text); assertEquals(EditorDiskState.Unchanged, editor.state.value.diskState)
            assertNull(editor.state.value.diskVersion); assertFalse(editor.state.value.diskChecking)
            val sameReady = CompletableDeferred<Unit>(); val sameRelease = CompletableDeferred<Unit>(); var pause = true
            lateinit var same: EditorController
            main { same = EditorController(f.wrapper, repo, {}, inspectDisk = { baseline ->
                val value = EditorFileStore(repo::directory).open(baseline.projectId, baseline.path)
                if (pause) { pause = false; sameReady.complete(Unit); withContext(NonCancellable) { sameRelease.await() } }
                value
            }) }; await { !same.state.value.loading }
            main { same.open(project.id, "b.kt") }; await { !same.state.value.loading }
            lateinit var pending: Job
            main { pending = same.checkDisk()!! }; sameReady.await()
            val saved = same.state.value.document!!
            main { saved.content.replace(0, saved.content.length, "same document saved"); same.changed(); same.save() }
            await { !same.state.value.saving && !same.state.value.diskChecking }
            sameRelease.complete(Unit); pending.join()
            assertSame(saved, same.state.value.document); assertEquals("same document saved", saved.baseline.text)
            assertEquals(EditorDiskState.Unchanged, same.state.value.diskState); assertNull(same.state.value.diskVersion)
            delay(600)
        }
    }
    @Test(timeout = 60000) fun durableDraftTabsPreserveSourceAndConflictBaseline() = runBlocking {
        Fixture(context).use { f ->
            val repo = ProjectRepository(f.wrapper, File(f.base, "workspaces").apply { mkdirs() })
            val project = withContext(Dispatchers.IO) { repo.create("owned fixture") }
            withContext(Dispatchers.IO) { File(repo.directory(project.id), "a.kt").writeText("before a"); File(repo.directory(project.id), "b.py").writeText("before b") }
            lateinit var editor: EditorController
            main { editor = EditorController(f.wrapper, repo, {}) }; await { !editor.state.value.loading }
            main { editor.open(project.id, "a.kt") }; await { !editor.state.value.loading && editor.state.value.document != null }
            main { editor.state.value.document!!.content.replace(0, 8, "draft a"); editor.changed(); editor.open(project.id, "b.py") }
            await { !editor.state.value.loading && editor.state.value.path == "b.py" }
            main { editor.state.value.document!!.content.replace(0, 8, "draft b"); editor.changed() }; delay(1000)
            lateinit var restored: EditorController
            main { restored = EditorController(f.wrapper, repo, {}) }; await { !restored.state.value.loading }
            assertEquals(2, restored.state.value.tabs.size)
            assertEquals("before a", withContext(Dispatchers.IO) { File(repo.directory(project.id), "a.kt").readText() })
            main { restored.open(project.id, "a.kt") }; assertEquals("draft a", restored.state.value.document!!.content.toString())
            withContext(Dispatchers.IO) { File(repo.directory(project.id), "a.kt").writeText("external edit") }
            main { restored.save() }; await { !restored.state.value.saving }; assertTrue(restored.state.value.conflict)
            assertEquals("external edit", withContext(Dispatchers.IO) { File(repo.directory(project.id), "a.kt").readText() })
            main { restored.open(project.id, "b.py") }; assertEquals("draft b", restored.state.value.document!!.content.toString())
            main { restored.close(); restored.open(project.id, "a.kt"); restored.close() }; delay(500)
            assertTrue(withContext(Dispatchers.IO) { EditorDraftStore(File(f.wrapper.noBackupFilesDir, "editor-drafts")).load() }.isEmpty())
        }
    }
    @Test(timeout = 90000) fun aiRulesReferencesCheckpointAndRestartAreWired() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                val root = File(f.root, "project").apply { mkdirs() }
                File(root, "AGENTS.md").writeText("PROJECT_RULE_MARKER")
                File(root, "code.py").writeText("before")
                val wrapper = object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "control").apply { mkdirs() } }
                fun controller() = AiController(wrapper, { root }, { _, _ -> false }, { _, _ -> error("No command expected") }, {}, {}, f.transport, f.config, history = f.history)
                lateinit var ai: AiController
                main { ai = controller(); ai.select("fixture") }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                f.enqueue(calls = listOf(AiCall("read", "read_file", "{\"path\":\"code.py\"}")))
                f.enqueue(calls = listOf(AiCall("write", "write_file", "{\"path\":\"code.py\",\"content\":\"after\",\"reason\":\"fixture edit\"}")))
                f.enqueue("Completed fixture edit; build not run.")
                main { ai.addReference(AiReference("code.py", "SELECTED_CODE_MARKER", "selection", 1, true)); ai.send("Edit fixture code") }
                await { ai.state.value.approval != null }
                assertNotNull(ai.state.value.checkpointId)
                assertEquals("before", File(root, "code.py").readText())
                val body = org.json.JSONObject(f.server.takeRequest().body.readUtf8())
                val messages = body.getJSONArray("messages").toString()
                assertTrue(messages.contains("PROJECT_RULE_MARKER")); assertTrue(messages.contains("SELECTED_CODE_MARKER"))
                assertFalse(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("PROJECT_RULE_MARKER"))
                main { ai.approve(true) }; await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                val key = ai.state.value.checkpointId!!
                assertEquals("after", File(root, "code.py").readText())
                lateinit var restarted: AiController
                main { restarted = controller(); restarted.select("fixture") }
                await { restarted.state.value.configured && !restarted.state.value.permissionsLoading && !restarted.state.value.skillsLoading }
                assertEquals(key, restarted.state.value.checkpointId)
                assertEquals("code.py", restarted.checkpointChanges(key).single().path)
                val sourceConversation = restarted.state.value.conversationId
                main { restarted.conversation() }
                await { !restarted.state.value.permissionsLoading && !restarted.state.value.skillsLoading && restarted.state.value.conversationId != sourceConversation }
                assertTrue(withContext(Dispatchers.Main) { restarted.restoreCheckpoint(key) })
                assertEquals("before", File(root, "code.py").readText())
                assertEquals(3, f.server.requestCount)
                repeat(2) { f.server.takeRequest() }
                lateinit var afterRestore: AiController
                main { afterRestore = controller(); afterRestore.select("fixture") }
                await { afterRestore.state.value.configured && !afterRestore.state.value.permissionsLoading && !afterRestore.state.value.skillsLoading }
                f.enqueue("Current files must be checked again.")
                main { assertTrue(afterRestore.send("Continue after restoration")) }
                await { !afterRestore.state.value.active }
                val continued = org.json.JSONObject(f.server.takeRequest().body.readUtf8()).getJSONArray("messages").toString()
                assertTrue(continued.contains("workspace_restoration_facts")); assertTrue(continued.contains(sourceConversation))
                assertTrue(continued.contains("completed")); assertTrue(continued.contains("code.py"))
                main { afterRestore.conversation(sourceConversation) }
                await { !afterRestore.state.value.permissionsLoading && !afterRestore.state.value.skillsLoading && afterRestore.state.value.conversationId == sourceConversation }
                f.enqueue("Old edits are historical; inspect current files.")
                main { assertTrue(afterRestore.send("Continue original conversation")) }
                await { !afterRestore.state.value.active }
                val original = org.json.JSONObject(f.server.takeRequest().body.readUtf8()).getJSONArray("messages").toString()
                assertTrue(original.contains("workspace_restoration_facts")); assertTrue(original.contains("completed"))
                assertEquals("before", File(root, "code.py").readText())
            }
        }
    }
    @Test(timeout = 90000) fun guestCommandsStreamFailTimeoutAndStopOwnedProcesses() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val app = (context.applicationContext as ZakoApplication).workbench
            await { !app.state.value.loading && !app.state.value.busy }; assertTrue(app.state.value.installed)
            Fixture(context).use { f ->
                val id = UUID.randomUUID().toString(); val root = File(app.runtime.workspaces, id).apply { mkdir() }
                lateinit var tasks: ProjectTasks
                try {
                    main { tasks = ProjectTasks(f.wrapper, { root }, { project, script, timeout, output -> app.runtime.execute(script, project, app.state.value.settings, timeoutMillis = timeout, output = output) }, { true }, {}); tasks.select(id) }
                    await { !tasks.state.value.loading }
                    val command = ProjectCommand("build", "printf 'first line\\n'; sleep 1; printf 'last line\\n'; exit 7", timeoutSeconds = 1800)
                    main { tasks.save(command) }; await { tasks.state.value.commands.size == 1 }
                    val result = tasks.observed(id, command.shell(), 1800000, {})
                    assertEquals(7, result.exitCode)
                    assertTrue(tasks.log(id, tasks.state.value.runs.first().id).contains("last line"))
                    val timeoutScript = "printf 'timeout started\\n'; sleep 30; touch timeout-late"
                    try { tasks.observed(id, timeoutScript, 1000, {}) } catch (_: TimeoutCancellationException) { }
                    val cancelScript = "printf 'cancel started\\n'; sleep 30; touch cancel-late"
                    val job = launch { tasks.observed(id, cancelScript, 60000, {}) }
                    await { tasks.state.value.log.contains("cancel started") }; job.cancelAndJoin()
                    val records = withContext(Dispatchers.IO) { tasks.runs(id) }
                    assertEquals("cancelled", records.single { it.script == cancelScript }.status)
                    assertEquals("timeout", records.single { it.script == timeoutScript }.status)
                    assertEquals("failed", records.single { it.kind == "build" }.status)
                    assertFalse(File(root, "timeout-late").exists()); assertFalse(File(root, "cancel-late").exists())
                } finally { withContext(NonCancellable + Dispatchers.IO) { root.deleteRecursively() } }
            }
        }
    }
    @Test(timeout = 180000) fun publicHttpsCloneCreatesOwnedProjectWithoutChangingExistingProjects() = runBlocking {
        org.junit.Assume.assumeTrue("Opt-in live network test", InstrumentationRegistry.getArguments().getString("externalNetwork") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val app = (context.applicationContext as ZakoApplication).workbench
            await { !app.state.value.loading && !app.state.value.busy }
            val original = app.state.value.projects.map { it.id }; val previous = app.state.value.selectedId
            val name = "clone-fixture-${System.currentTimeMillis()}"; var owned: String? = null
            try {
                main { app.cloneProject(name, "https://github.com/octocat/Hello-World.git") }
                withTimeout(120000) { while (app.state.value.busy) delay(100) }
                val project = app.state.value.project
                assertEquals(app.state.value.message + "\n" + app.state.value.log.takeLast(3000), name, project?.name); owned = project!!.id
                assertTrue(withContext(Dispatchers.IO) { File(app.projects.directory(project.id), ".git").isDirectory })
                assertEquals(original.toSet(), app.state.value.projects.filter { it.id != owned }.map { it.id }.toSet())
                val remote = app.runtime.execute("$GIT_PREFIX remote get-url origin", project.id, app.state.value.settings)
                assertTrue(remote.output.contains("https://github.com/octocat/Hello-World.git"))
            } finally { withContext(NonCancellable) {
                if (app.state.value.busy) { main { app.cancel() }; await { !app.state.value.busy } }
                owned?.let { id -> main { app.removeProject(id) }; await { !app.state.value.busy && app.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { app.select(id) }; await { !app.state.value.busy } }
            } }
        }
    }
    @Test(timeout = 120000) fun guestGitStatusStageAndExplicitIdentityCommit() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val app = (context.applicationContext as ZakoApplication).workbench
            await { !app.state.value.loading && !app.state.value.busy }
            assertTrue("Ubuntu required for this test", app.state.value.installed)
            val id = UUID.randomUUID().toString()
            val root = File(app.runtime.workspaces, id)
            withContext(Dispatchers.IO) { root.mkdir(); File(root, "space ' quoted.txt").writeText("fixture data") }
            try {
                lateinit var git: ProjectGit
                main { git = ProjectGit(context, { project, script -> app.runtime.execute(script, project, app.state.value.settings, timeoutMillis = 30000) }, { true }); git.select(id) }
                await { !git.state.value.busy }
                assertFalse(git.state.value.repository)
                main { git.initialize() }; await { !git.state.value.busy }; assertTrue(git.state.value.error, git.state.value.repository)
                assertEquals("space ' quoted.txt", git.state.value.entries.single().path)
                main { git.stage(setOf("space ' quoted.txt")) }; await { !git.state.value.busy }; assertEquals('A', git.state.value.entries.single().index)
                main { git.commit("test: verify quoted path", "Workflow Test", "workflow@example.invalid") }; await { !git.state.value.busy }
                assertEquals("", git.state.value.error); assertTrue(git.state.value.entries.isEmpty())
                val log = app.runtime.execute("$GIT_PREFIX log -1 --format=%s", id, app.state.value.settings)
                assertTrue(log.output.contains("test: verify quoted path"))
            } finally { withContext(NonCancellable + Dispatchers.IO) { root.deleteRecursively() } }
        }
    }
}
