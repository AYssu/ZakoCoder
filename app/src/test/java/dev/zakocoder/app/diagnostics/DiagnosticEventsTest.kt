package dev.zakocoder.app.diagnostics

import dev.zakocoder.app.ai.AiApiProtocol
import dev.zakocoder.app.ai.AiFailure
import dev.zakocoder.app.ai.AiRequestLog
import dev.zakocoder.app.ai.RequestDiagnosticTrace
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime

class DiagnosticEventsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun snapshotRebuildsOnlyTypedWhitelistAndRejectsInvalidIdentifiers() {
        val time = System.currentTimeMillis()
        val valid = DiagnosticEvents.encode(DiagnosticEvent(DiagnosticStage.Request,
            DiagnosticOutcome.Failed, DiagnosticId.create(), failure = AiFailure.Network, bytes = 12), time)
        valid.put("url", "https://private.invalid").put("key", "secret-value").put("model", "private-model")
            .put("diagnostic", "provider-controlled").put("events", -1).put("phase", "provider-controlled")
        val decoded = requireNotNull(DiagnosticEvents.decode(valid.toString(), time - 1)).toString()
        assertFalse(decoded.contains("secret-value"))
        assertFalse(decoded.contains("private.invalid"))
        assertFalse(decoded.contains("private-model"))
        assertFalse(decoded.contains("provider-controlled"))
        assertFalse(JSONObject(decoded).has("events"))
        assertEquals(12L, JSONObject(decoded).getLong("bytes"))
        valid.put("id", "not-a-locally-generated-uuid")
        assertNull(DiagnosticEvents.decode(valid.toString(), time - 1))
    }

    @Test fun snapshotFlushesAcceptedEventsAndExpiresOlderEntries() = runBlocking {
        val directory = temporary.newFolder()
        val now = System.currentTimeMillis()
        val old = DiagnosticEvents.encode(DiagnosticEvent(DiagnosticStage.Startup,
            DiagnosticOutcome.Started, DiagnosticId.create()), now - DiagnosticEvents.RETENTION_MILLIS - 10)
        File(directory, "events.jsonl").writeText(old.toString() + "\n")
        val logger = DiagnosticEvents.forTests(directory)
        val id = DiagnosticId.create()
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, id, active = true))
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Succeeded, id, active = false))
        val snapshot = logger.exportSnapshot()
        val lines = snapshot.jsonl.lineSequence().filter { it.isNotEmpty() }.map(::JSONObject).toList()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.getString("id") == id.value })
        assertEquals("Started", lines.first().getString("outcome"))
        assertEquals("Succeeded", lines.last().getString("outcome"))
        assertEquals(0L, snapshot.droppedEvents)
        assertEquals(0L, snapshot.writeFailures)
    }

    @Test fun fileRotationHasFixedTotalBudgetAndStillExportsBothFiles() = runBlocking {
        val directory = temporary.newFolder()
        val line = DiagnosticEvents.encode(DiagnosticEvent(DiagnosticStage.Service,
            DiagnosticOutcome.Changed, DiagnosticId.create()), System.currentTimeMillis()).toString() + "\n"
        val current = File(directory, "events.jsonl")
        current.writeText(line.repeat((DiagnosticEvents.MAX_FILE_BYTES / line.toByteArray().size).toInt()))
        val logger = DiagnosticEvents.forTests(directory)
        repeat(3) { logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, DiagnosticId.create())) }
        val result = logger.exportSnapshot()
        assertTrue(File(directory, "events.previous.jsonl").isFile)
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= DiagnosticEvents.MAX_FILE_BYTES * 2)
        assertTrue(result.jsonl.contains("Service"))
        assertTrue(result.jsonl.contains("Task"))
    }

    @Test fun storageFailureIsCountedWithoutFailingTaskOrSnapshot() = runBlocking {
        val directoryIsFile = temporary.newFile()
        val logger = DiagnosticEvents.forTests(directoryIsFile)
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, DiagnosticId.create()))
        val snapshot = logger.exportSnapshot()
        assertTrue(snapshot.jsonl.isEmpty())
        assertEquals(2L, snapshot.writeFailures)
    }

    @Test fun linkedDiagnosticDirectoryIsRejectedWithoutWritingIntoTarget() = runBlocking {
        val target = temporary.newFolder()
        val sentinel = File(target, "sentinel.txt").apply { writeText("keep target unchanged") }
        val link = File(temporary.newFolder(), "diagnostics")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val logger = DiagnosticEvents.forTests(link)
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, DiagnosticId.create()))
        val snapshot = logger.exportSnapshot()
        assertTrue(snapshot.jsonl.isEmpty())
        assertEquals(2L, snapshot.writeFailures)
        assertEquals("keep target unchanged", sentinel.readText())
        assertEquals(listOf("sentinel.txt"), target.listFiles().orEmpty().map { it.name })
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun linkedCurrentAndPreviousLogsAreRejectedWithoutReadingOrModifyingTarget() = runBlocking {
        for (name in listOf("events.jsonl", "events.previous.jsonl")) {
            val directory = temporary.newFolder()
            val target = temporary.newFile().apply {
                writeText(DiagnosticEvents.encode(DiagnosticEvent(DiagnosticStage.Screen,
                    DiagnosticOutcome.Succeeded, DiagnosticId.create()), System.currentTimeMillis()).toString() + "\n")
            }
            val unchanged = target.readBytes()
            val link = File(directory, name)
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val logger = DiagnosticEvents.forTests(directory)
            logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, DiagnosticId.create()))
            val snapshot = logger.exportSnapshot()
            assertTrue(snapshot.jsonl.isEmpty())
            assertEquals(2L, snapshot.writeFailures)
            assertArrayEquals(unchanged, target.readBytes())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals(1, directory.listFiles().orEmpty().size)
        }
    }

    @Test fun danglingLogLinkDoesNotCreateItsTarget() = runBlocking {
        val directory = temporary.newFolder()
        val absentTarget = File(temporary.newFolder(), "absent-target")
        val link = File(directory, "events.jsonl")
        Files.createSymbolicLink(link.toPath(), absentTarget.toPath())
        val logger = DiagnosticEvents.forTests(directory)
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, DiagnosticId.create()))
        val snapshot = logger.exportSnapshot()
        assertTrue(snapshot.jsonl.isEmpty())
        assertEquals(2L, snapshot.writeFailures)
        assertFalse(Files.exists(absentTarget.toPath(), NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun oversizedLogIsRejectedWithoutReadingAppendingOrRotatingIt() = runBlocking {
        val directory = temporary.newFolder()
        val current = File(directory, "events.jsonl").apply { writeBytes(ByteArray(DiagnosticEvents.MAX_FILE_BYTES.toInt() + 1) { 'x'.code.toByte() }) }
        val previous = File(directory, "events.previous.jsonl").apply { writeText("keep previous") }
        val logger = DiagnosticEvents.forTests(directory)
        logger.record(DiagnosticEvent(DiagnosticStage.Task, DiagnosticOutcome.Started, DiagnosticId.create()))
        val snapshot = logger.exportSnapshot()
        assertTrue(snapshot.jsonl.isEmpty())
        assertEquals(2L, snapshot.writeFailures)
        assertEquals(DiagnosticEvents.MAX_FILE_BYTES + 1, current.length())
        assertEquals("keep previous", previous.readText())
    }

    @Test fun snapshotRemovesExpiredRegularLogWithoutRequiringAnotherEvent() = runBlocking {
        val directory = temporary.newFolder()
        val current = File(directory, "events.jsonl").apply { writeText("expired") }
        Files.setLastModifiedTime(current.toPath(), FileTime.fromMillis(System.currentTimeMillis() - DiagnosticEvents.RETENTION_MILLIS - 10_000))
        val snapshot = DiagnosticEvents.forTests(directory).exportSnapshot()
        assertTrue(snapshot.jsonl.isEmpty())
        assertEquals(0L, snapshot.writeFailures)
        assertFalse(Files.exists(current.toPath(), NOFOLLOW_LINKS))
    }

    @Test fun requestProjectionThrottlesProgressAndNeverCopiesPayloadOrEndpoint() = runBlocking {
        val logger = DiagnosticEvents.forTests(temporary.newFolder())
        val trace = RequestDiagnosticTrace(logger, DiagnosticId.create(), AiApiProtocol.Responses, 1)
        val base = AiRequestLog(model = "private-model", origin = "https://private.invalid", request = "prompt secret-value", response = "user-output",
            status = 200, firstEvent = 1)
        for (tick in 1..1000) trace.update(base.copy(elapsed = tick * 5L, bytes = tick.toLong(), events = tick))
        trace.update(base.copy(elapsed = 5010, bytes = 1000, events = 1000, complete = true))
        trace.finish(DiagnosticOutcome.Failed, AiFailure.Internal)
        val snapshot = logger.exportSnapshot()
        assertFalse(snapshot.jsonl.contains("private-model"))
        assertFalse(snapshot.jsonl.contains("private.invalid"))
        assertFalse(snapshot.jsonl.contains("secret-value"))
        assertFalse(snapshot.jsonl.contains("user-output"))
        val lines = snapshot.jsonl.lineSequence().filter { it.isNotEmpty() }.map(::JSONObject).toList()
        assertEquals(1, lines.count { it.getString("outcome") == "Started" })
        assertEquals(1, lines.count { it.getString("outcome") == "FirstByte" })
        assertEquals(1, lines.count { it.getString("outcome") == "Headers" })
        assertEquals(5, lines.count { it.getString("outcome") == "Progress" })
        assertEquals(1, lines.count { it.getString("outcome") == "Succeeded" })
        assertTrue(lines.none { it.getString("outcome") == "Failed" })
    }
}
