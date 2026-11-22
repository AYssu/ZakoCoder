package dev.zakocoder.app.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidSharedStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deniedPermissionDoesNotExposeAnExistingDirectory() {
        val directory = temporary.newFolder("primary")
        val mounts = AndroidSharedStorage.plan(directory, authorized = false)

        assertNull(mounts.source)
        assertTrue(mounts.paths.isEmpty())
        assertTrue(mounts.arguments.isEmpty())
        assertTrue(mounts.modelContext().contains("not mounted"))
        assertTrue(mounts.modelContext().contains("Permission management"))
        assertTrue(mounts.modelContext().contains("new command or terminal"))
    }

    @Test fun authorizedAliasesUseOneRealSourceWithoutGuessingUserZero() {
        val directory = File(temporary.root, "storage/emulated/12").apply { mkdirs() }
        val mounts = AndroidSharedStorage.plan(directory, authorized = true)

        assertEquals(directory.canonicalFile, mounts.source)
        assertEquals(listOf(directory.absolutePath, "/sdcard", "/storage/self/primary"), mounts.paths)
        assertFalse(mounts.paths.contains("/storage/emulated/0"))
        assertEquals(mounts.paths.flatMap { listOf("-b", "${directory.canonicalPath}:$it") }, mounts.arguments)
    }

    @Test fun absentOrNonDirectorySourcesAreNotMountedEvenWhenAuthorized() {
        for (directory in listOf(File(temporary.root, "missing"), temporary.newFile("regular-file"))) {
            val mounts = AndroidSharedStorage.plan(directory, authorized = true)
            assertNull(mounts.source)
            assertTrue(mounts.paths.isEmpty())
            assertTrue(mounts.arguments.isEmpty())
        }
    }

    @Test fun mountedContextExplainsOriginalFilesApprovalAndCheckpointBoundary() {
        val mounts = AndroidSharedStorage.plan(temporary.newFolder("primary"), authorized = true)
        val context = mounts.modelContext()

        mounts.paths.forEach { assertTrue(context, context.contains(it)) }
        assertTrue(context, context.contains("same real phone files, not workspace copies"))
        assertTrue(context, context.contains("approved run_command"))
        assertTrue(context, context.contains("structured file tools remain workspace-scoped"))
        assertTrue(context, context.contains("outside the workspace are not covered by its checkpoints"))
        assertTrue(context, context.contains("private app directories remain subject to system restrictions"))
    }
}
