package dev.zakocoder.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkbenchLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectedServiceStartClearsBusyOnceAndPreservesThePrimaryFailure() {
        val transitions = mutableListOf<Boolean>()
        var starts = 0
        var releases = 0

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withBusyCommand<Unit>(
                    setBusy = { transitions += it },
                    startService = {
                        starts += 1
                        throw IllegalStateException("start failed")
                    },
                    releaseService = {
                        releases += 1
                        throw IllegalStateException("release failed")
                    },
                    command = { throw AssertionError("command must not run") }
                )
            }
        }

        assertEquals("start failed", failure.message)
        assertEquals(listOf(true, false), transitions)
        assertEquals(1, starts)
        assertEquals(1, releases)
    }

    @Test fun deletionTombstoneStaysBesideSourceWithEpochSuffix() {
        val source = File(temporary.newFolder(), "project-id").apply { check(mkdir()) }
        val tombstone = projectDeletionTombstone(source, 1234)

        assertEquals(source.parentFile.canonicalFile, tombstone.parentFile.canonicalFile)
        assertEquals("project-id.deleting-1234", tombstone.name)
        assertFalse(tombstone.exists())
    }
}
