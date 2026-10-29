package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class ProjectEvidenceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(block: () -> Boolean) = withTimeout(30000) { while (!block()) delay(30) }
    private class Owned(context: android.content.Context) : ContextWrapper(context), AutoCloseable {
        val base = File(context.cacheDir, "evidence-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val project = File(base, "project").apply { mkdir() }
        override fun getFilesDir() = File(base, "control").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(base, "private").apply { mkdirs() }
        override fun close() { base.deleteRecursively() }
    }
    private fun tasks(o: Owned, execute: suspend (String, String, Long, (String) -> Unit) -> CommandResult = { _, _, _, _ -> CommandResult(0, "checked") }) =
        ProjectTasks(o, { o.project }, execute, { true }, {})
    private suspend fun configure(t: ProjectTasks, command: ProjectCommand) {
        main { t.select("p") }; await { !t.state.value.loading }
        main { t.save(command) }; await { !t.state.value.saving }; assertEquals("", t.state.value.error)
    }
    private suspend fun stamp(o: Owned) = ProjectSourceEvidence { o.project }.capture("p", currentCoroutineContext())

    @Test(timeout = 90000) fun unchangedRunSurvivesRestartButEverySourceMutationInvalidatesItsScope() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o ->
            val source = File(o.project, "main.py").apply { writeText("before\n") }
            val t = tasks(o); configure(t, ProjectCommand("test", "verify"))
            t.observed("p", "verify", 1800000, {})
            val run = tasks(o).runs("p").single()
            assertEquals("passed", run.status); assertEquals(0, run.exit); assertEquals(ProjectVerification.Unchanged, run.verification(stamp(o)))
            source.writeText("after\n"); assertEquals(ProjectVerification.Changed, run.verification(stamp(o)))
            assertTrue(t.modelContext("p").contains("\"source_verification\":\"Changed\""))
            source.writeText("before\n"); assertEquals(ProjectVerification.Unchanged, run.verification(stamp(o)))
            val extra = File(o.project, "added.txt").apply { writeText("new") }; assertEquals(ProjectVerification.Changed, run.verification(stamp(o)))
            extra.delete(); File(o.project, "empty").mkdir(); assertEquals(ProjectVerification.Changed, run.verification(stamp(o))); File(o.project, "empty").delete()
            val renamed = File(o.project, "renamed.py"); assertTrue(source.renameTo(renamed)); assertEquals(ProjectVerification.Changed, run.verification(stamp(o))); assertTrue(renamed.renameTo(source))
            val oldMode = Files.getPosixFilePermissions(source.toPath()); Files.setPosixFilePermissions(source.toPath(), PosixFilePermissions.fromString("r--------"))
            assertEquals(ProjectVerification.Changed, run.verification(stamp(o))); Files.setPosixFilePermissions(source.toPath(), oldMode)
            source.delete(); assertEquals(ProjectVerification.Changed, run.verification(stamp(o)))
            assertEquals("passed", tasks(o).runs("p").single().status)
            assertEquals("unchanged", run.configuration(listOf(ProjectCommand("test", "verify"))))
            assertEquals("changed", run.configuration(listOf(ProjectCommand("test", "verify-new"))))
            assertEquals("changed", run.configuration(listOf(ProjectCommand("test", "verify", "src"))))
            assertEquals("timeout_changed", run.configuration(listOf(ProjectCommand("test", "verify", timeoutSeconds = 5))))
            val records = File(o.noBackupFilesDir, "project-tasks-v1").listFiles()!!.single().resolve("runs.json")
            val oldJson = JSONObject(records.readText()); oldJson.getJSONArray("runs").getJSONObject(0).apply {
                remove("sourceBefore"); remove("sourceAfter"); remove("timeoutMillis")
            }
            records.writeText(oldJson.toString())
            val legacy = tasks(o).runs("p").single(); assertEquals(0, legacy.exit); assertNull(legacy.sourceBefore); assertNull(legacy.sourceAfter)
            assertEquals(ProjectVerification.Unknown, legacy.verification(stamp(o)))
        }
    }
    @Test(timeout = 90000) fun successfulMutatingCommandAndCancellationKeepExitTruthSeparateFromSourceState() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o ->
            val source = File(o.project, "main.py").apply { writeText("before") }
            val t = tasks(o) { _, _, _, output -> source.writeText("after"); output("real command output"); CommandResult(0, "real command output") }
            t.observed("p", "format-and-test", 1000, {})
            val run = t.runs("p").single(); assertEquals(0, run.exit); assertEquals("passed", run.status)
            assertEquals(ProjectVerification.ChangedDuringRun, run.verification(stamp(o)))
            val ready = CompletableDeferred<Unit>()
            val cancellable = tasks(o) { _, _, _, _ -> source.writeText("cancelled-version"); ready.complete(Unit); awaitCancellation() }
            val job = launch { cancellable.observed("p", "long-running", 1000, {}) }
            ready.await(); job.cancelAndJoin()
            val cancelled = cancellable.runs("p").single { it.script == "long-running" }
            assertEquals("cancelled", cancelled.status); assertNull(cancelled.exit); assertNotNull(cancelled.sourceAfter)
            assertEquals(ProjectVerification.ChangedDuringRun, cancelled.verification(stamp(o)))
            val timed = tasks(o) { _, _, _, _ -> withTimeout(30) { delay(5000) }; CommandResult(0, "") }
            try { timed.observed("p", "timeout-command", 1000, {}); fail("Timeout must propagate") } catch (_: ProjectCommandFailure) { }
            assertEquals("timeout", timed.runs("p").single { it.script == "timeout-command" }.status)
        }
    }
    @Test(timeout = 90000) fun binaryBytesAndDirectoryModesAreHashedButSensitiveAndGeneratedBodiesAreExcluded() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o ->
            val binary = File(o.project, "image.bin").apply { writeBytes(byteArrayOf(0, 1, 2)) }
            val before = stamp(o); assertFalse(before.limited)
            binary.writeBytes(byteArrayOf(0, 1, 3)); assertNotEquals(before.digest, stamp(o).digest)
            binary.writeBytes(byteArrayOf(0, 1, 2))
            File(o.project, ".env").writeText("OWNED_TEST_SENSITIVE_EXCLUDED")
            File(o.project, "node_modules").mkdir(); File(o.project, "node_modules/a.txt").writeText("generated")
            assertEquals(before.digest, stamp(o).digest); assertFalse(stamp(o).limited)
            val dir = File(o.project, "empty").apply { mkdir() }; val initial = stamp(o)
            val mode = Files.getPosixFilePermissions(dir.toPath()); Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-x------"))
            assertNotEquals(initial.digest, stamp(o).digest); Files.setPosixFilePermissions(dir.toPath(), mode)
            Files.createSymbolicLink(File(o.project, "linked").toPath(), binary.toPath()); assertTrue(stamp(o).limited)
            File(o.project, "linked").delete()
            Files.setPosixFilePermissions(binary.toPath(), PosixFilePermissions.fromString("---------"))
            try { assertTrue(stamp(o).limited) } finally { Files.setPosixFilePermissions(binary.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
    }
    @Test(timeout = 90000) fun boundedUnknownAndLegacyEvidenceNeverBecomeCurrentVerification() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o ->
            repeat(501) { File(o.project, "file$it").writeText("x") }
            val bounded = stamp(o); assertTrue(bounded.limited); assertEquals(500, bounded.files)
            val legacy = ProjectRun(java.util.UUID.randomUUID().toString(), "test", "old", 1, "passed", 0)
            assertEquals(ProjectVerification.Unknown, legacy.verification(bounded))
            assertEquals(ProjectVerification.Unknown, legacy.copy(sourceBefore = bounded, sourceAfter = bounded).verification(bounded))
            assertEquals(ProjectVerification.Unknown, legacy.copy(sourceBefore = bounded, sourceAfter = bounded.copy(digest = "0".repeat(64))).verification(bounded))
            val future = bounded.encode().put("version", 999); assertNull(ProjectSourceStamp.decode(future))
            val cancelled = Job().apply { cancel() }
            try { ProjectSourceEvidence { o.project }.capture("p", cancelled); fail("Capture must be cancellable") } catch (_: CancellationException) { }
            o.project.listFiles()!!.forEach { it.delete() }
            File(o.project, "large.bin").writeBytes(ByteArray(1048577)); assertTrue(stamp(o).limited)
            File(o.project, "large.bin").delete()
            repeat(17) { File(o.project, "chunk$it.bin").writeBytes(ByteArray(1048576)) }
            val totalBound = stamp(o); assertTrue(totalBound.limited); assertEquals(16, totalBound.files)
            o.project.listFiles()!!.forEach { it.delete() }
            var deep = o.project
            repeat(33) { deep = File(deep, "d").apply { mkdir() } }
            assertTrue(stamp(o).limited)
        }
    }
    @Test(timeout = 120000) fun everyAiRequestRefreshesRecordedEvidenceAfterFileWriteWithoutReplayingCommands() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o -> AiDeviceTest.Fixture(context).use { f ->
            File(o.project, "main.py").writeText("before")
            var executions = 0
            val t = tasks(o) { _, _, _, _ -> executions++; CommandResult(0, "test output") }
            configure(t, ProjectCommand("test", "verify")); t.observed("p", "verify", 1800000, {})
            lateinit var ai: AiController
            main { ai = AiController(o, { o.project }, { _, _ -> false }, { _, _ -> error("No command expected") }, {}, {}, f.transport, f.config, history = f.history,
                projectCommandContext = { id -> t.modelContext(id, ai.state.value.policy.permission(AiTool.ReadFile) == AiPermission.Allow) }); ai.select("p") }
            await { ai.state.value.configured && !ai.state.value.skillsLoading && !ai.state.value.permissionsLoading }
            try {
                f.enqueue(calls = listOf(AiCall("read", "read_file", """{"path":"main.py"}""")))
                f.enqueue(calls = listOf(AiCall("write", "write_file", """{"path":"main.py","content":"after"}""")))
                f.enqueue("源文件已变化，旧测试不能验收新代码。")
                main { ai.send("修改源文件") }; await { ai.state.value.approval != null }; main { ai.approve(true) }; await { !ai.state.value.active }
                assertEquals("", ai.state.value.error); assertEquals(1, executions)
                val requests = (1..3).map { f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8() }
                assertTrue(requests.first().contains("Unchanged")); assertTrue(requests.last().contains("Changed")); assertEquals("after", File(o.project, "main.py").readText())
                val disabled = t.modelContext("p", false); assertTrue(disabled.contains("Unknown")); assertFalse(disabled.contains("\"source_files\":1"))
            } finally { main { ai.stop() }; await { !ai.state.value.active } }
        } }
    }
    @Test(timeout = 90000) fun delayedOldRefreshCannotOverwriteCommandCompletionEvidence() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o ->
            val source = File(o.project, "main.py").apply { writeText("before") }
            val sampled = CompletableDeferred<ProjectSourceStamp>(); val release = CompletableDeferred<Unit>()
            val count = java.util.concurrent.atomic.AtomicInteger()
            val t = ProjectTasks(o, { o.project }, { _, _, _, _ -> source.writeText("after"); CommandResult(0, "done") }, { true }, {}, captureSource = {
                val result = stamp(o)
                if (count.incrementAndGet() == 1) { sampled.complete(result); release.await() }
                result
            })
            main { t.select("p") }; await { !t.state.value.loading }
            lateinit var refresh: Job
            main { refresh = requireNotNull(t.refreshEvidence()) }; val old = sampled.await()
            try {
                t.observed("p", "modify", 1000, {})
                val after = t.state.value.currentSource!!; assertNotEquals(old.digest, after.digest)
                release.complete(Unit); refresh.join()
                assertEquals(after, t.state.value.currentSource); assertFalse(t.state.value.sourceChecking)
                assertEquals(ProjectVerification.ChangedDuringRun, t.state.value.runs.single().verification(t.state.value.currentSource))
            } finally { release.complete(Unit) }
        }
    }
    @Test(timeout = 90000) fun deniedOrRevokedAutomaticReadPermissionNeverTriggersUnapprovedCommandSampling() = runBlocking(Dispatchers.IO) {
        Owned(context).use { o ->
            File(o.project, "main.py").writeText("before")
            val samples = java.util.concurrent.atomic.AtomicInteger(); var executeCount = 0
            var allowed = false
            val t = ProjectTasks(o, { o.project }, { _, _, _, _ -> executeCount++; allowed = false; CommandResult(0, "actual output") }, { true }, {}, captureSource = {
                samples.incrementAndGet(); stamp(o)
            })
            t.observed("p", "allowed-command-denied-reads", 1000, {}, source = "ai", inspectSource = { allowed })
            assertEquals(1, executeCount); assertEquals(0, samples.get())
            val denied = t.runs("p").single(); assertEquals(0, denied.exit); assertNull(denied.sourceBefore); assertNull(denied.sourceAfter)
            t.modelContext("p", false); assertEquals(0, samples.get())
            allowed = true
            t.observed("p", "revoke-during-command", 1000, {}, source = "ai", inspectSource = { allowed })
            assertEquals(2, executeCount); assertEquals(1, samples.get())
            val revoked = t.runs("p").single { it.script == "revoke-during-command" }
            assertNotNull(revoked.sourceBefore); assertNull(revoked.sourceAfter); assertEquals(ProjectVerification.Unknown, revoked.verification(null))
        }
    }
}
