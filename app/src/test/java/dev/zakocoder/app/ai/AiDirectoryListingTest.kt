package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AiDirectoryListingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryFileAndMissingDirectoryFailWhileRealEmptyDirectorySucceeds() {
        val root = temporary.newFolder("project")
        val context = AiTaskContext { root }
        File(root, "not-a-directory").writeText("owned fixture")
        File(root, "empty").mkdir()
        for (path in listOf("not-a-directory", "missing")) {
            try {
                context.list("owned", path)
                fail("Unreadable listing must not become an empty success: $path")
            } catch (_: IOException) { /* expected actual File.listFiles() == null */ }
        }
        assertTrue(context.list("owned", "empty").isEmpty())
        assertEquals("owned fixture", File(root, "not-a-directory").readText())
    }
}
