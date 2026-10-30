package dev.zakocoder.app.projects

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalProjectBindingsTest {
    private lateinit var sandbox: File
    private lateinit var managed: File
    private lateinit var privateRoot: File
    private lateinit var project: File
    private lateinit var policy: LocalProjectBindings

    @Before fun setup() {
        sandbox = Files.createTempDirectory("local-project-binding-").toFile()
        managed = File(sandbox, "managed").apply { mkdirs() }
        privateRoot = File(sandbox, "private").apply { mkdirs() }
        project = File(sandbox, "my project").apply { mkdirs() }
        policy = LocalProjectBindings(managed, listOf(privateRoot))
    }
    @After fun cleanup() { sandbox.deleteRecursively() }

    @Test fun boundDirectoryIsOriginalAndWritesReachOriginalFiles() {
        val file = File(project, "main.py").apply { writeText("before") }
        val bound = policy.validate(project.path)
        assertEquals(project.canonicalFile, bound)
        File(bound, "main.py").writeText("after")
        assertEquals("after", file.readText())
        assertEquals(listOf("main.py"), project.list()!!.toList())
    }

    @Test fun rejectsDuplicateParentAndChildBindings() {
        val child = File(project, "child").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { policy.validate(project.path, listOf(project.path)) }
        assertThrows(IllegalArgumentException::class.java) { policy.validate(child.path, listOf(project.path)) }
        assertThrows(IllegalArgumentException::class.java) { policy.validate(project.path, listOf(child.path)) }
        val sibling = File(sandbox, "my project-extra").apply { mkdirs() }
        assertEquals(sibling, policy.validate(sibling.path, listOf(project.path)))
    }

    @Test fun rejectsManagedAndPrivateOverlapInBothDirections() {
        val managedChild = File(managed, "child").apply { mkdirs() }
        val privateChild = File(privateRoot, "child").apply { mkdirs() }
        listOf(managed, privateRoot, managedChild, privateChild, sandbox).forEach {
            assertThrows(IllegalArgumentException::class.java) { policy.validate(it.path) }
        }
    }

    @Test fun rejectsSymlinkRootsAndSymlinkAncestorsOnEachUse() {
        val alias = File(sandbox, "alias")
        Files.createSymbolicLink(alias.toPath(), project.toPath())
        val child = File(project, "child").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { policy.validate(alias.path) }
        assertThrows(IllegalArgumentException::class.java) { policy.validate(File(alias, "child").path) }
        assertEquals(project, policy.validate(project.path))
        assertTrue(child.delete())
        assertTrue(project.delete())
        Files.createSymbolicLink(project.toPath(), managed.toPath())
        assertThrows(IllegalArgumentException::class.java) { policy.validate(project.path) }
    }

    @Test fun rejectsMissingRegularRelativeTraversalAndUnsafeNames() {
        val regular = File(sandbox, "file").apply { writeText("content") }
        listOf("", "relative/project", "/", "/storage", regular.path, File(sandbox, "missing").path,
            project.path + "/../my project", project.path + "/bad\\name", project.path + "/name:with:colon", project.path + "\n").forEach {
            assertThrows("must reject $it", IllegalArgumentException::class.java) { policy.validate(it) }
        }
        val android = File(sandbox, "Android/data/project").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { policy.validate(android.path) }
    }

    @Test fun idsOnlyAcceptCanonicalUuid() {
        assertTrue(LocalProjectBindings.validId("123e4567-e89b-12d3-a456-426614174000"))
        listOf("../outside", "1-1-1-1-1", "123E4567-E89B-12D3-A456-426614174000", "").forEach {
            assertFalse(LocalProjectBindings.validId(it))
        }
    }
}
