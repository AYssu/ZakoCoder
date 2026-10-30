package dev.zakocoder.app

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.LocalDirectoryBrowser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Owned private fixtures verify navigation without requesting access to users' shared files. */
class LocalDirectoryBrowserDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: () -> Boolean) = withTimeout(10000) { while (!check()) delay(20) }
    private suspend fun fixture(block: suspend (File, CoroutineScope) -> Unit) {
        val own = withContext(Dispatchers.IO) {
            File(context.cacheDir, "directory-browser-${UUID.randomUUID()}").apply { check(mkdirs()) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { block(own, scope) }
        finally {
            scope.cancel()
            withContext(Dispatchers.IO) {
                // walkFileTree does not follow symbolic links unless explicitly requested.
                Files.walkFileTree(own.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }

    @Test(timeout = 45000) fun navigationIsAsynchronousAndPrivatePathsAreNotSelectable() = runBlocking {
        fixture { own, scope ->
            val volume = File(own, "volume")
            val project = File(volume, "123 测试")
            withContext(Dispatchers.IO) {
                check(File(project, "src").mkdirs())
                check(File(volume, "Android").mkdirs())
                File(volume, "file.txt").writeText("not a directory")
            }
            val browser = LocalDirectoryBrowser(context, scope) {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                listOf(volume)
            }
            main { browser.open() }
            await { !browser.state.value.loading }
            val root = browser.state.value
            assertEquals("", root.error); assertEquals(volume.canonicalPath, root.current!!.path)
            assertNull(root.parent); assertFalse(root.selectable)
            assertEquals(listOf("123 测试"), root.directories.map { it.name })
            main { browser.open(project.absolutePath) }
            await { !browser.state.value.loading }
            assertEquals(project.canonicalPath, browser.state.value.current!!.path)
            assertEquals(volume.canonicalPath, browser.state.value.parent)
            assertFalse("Private fixture descendants must never become bindable", browser.state.value.selectable)
            assertEquals(listOf("src"), browser.state.value.directories.map { it.name })
        }
    }

    @Test(timeout = 45000) fun symlinksEscapesAndUnavailablePathsAreNotMistakenForEmptyFolders() = runBlocking {
        fixture { own, scope ->
            val volume = File(own, "volume")
            val empty = File(volume, "empty")
            val outside = File(own, "outside")
            val link = File(volume, "alias")
            withContext(Dispatchers.IO) {
                check(empty.mkdirs()); check(outside.mkdirs())
                File(outside, "sentinel.txt").writeText("untouched")
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            }
            val browser = LocalDirectoryBrowser(context, scope) { listOf(volume) }
            main { browser.open(volume.absolutePath) }; await { !browser.state.value.loading }
            assertEquals(listOf("empty"), browser.state.value.directories.map { it.name })
            for (invalid in listOf(link.path, outside.path, File(volume, "../outside").path, File(volume, "missing").path)) {
                main { browser.open(invalid) }; await { !browser.state.value.loading }
                assertTrue("Unavailable paths need a visible error", browser.state.value.error.isNotEmpty())
                assertNull(browser.state.value.current); assertFalse(browser.state.value.selectable)
                assertTrue(browser.state.value.directories.isEmpty())
            }
            main { browser.open(empty.path) }; await { !browser.state.value.loading }
            assertEquals("", browser.state.value.error)
            assertEquals(empty.canonicalPath, browser.state.value.current!!.path)
            assertTrue(browser.state.value.directories.isEmpty())
            assertEquals("untouched", withContext(Dispatchers.IO) { File(outside, "sentinel.txt").readText() })
        }
    }

    @Test(timeout = 45000) fun cancelledOldListingCannotReplaceTheLatestDirectory() = runBlocking {
        fixture { own, scope ->
            val volume = File(own, "volume")
            val newer = File(volume, "newer")
            withContext(Dispatchers.IO) { check(newer.mkdirs()) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val browser = LocalDirectoryBrowser(context, scope) {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                }
                listOf(volume)
            }
            try {
                main { browser.open(volume.path) }
                await { entered.count == 0L }
                main { browser.open(newer.path) }
                await { !browser.state.value.loading }
                assertEquals(newer.canonicalPath, browser.state.value.current!!.path)
                release.countDown()
                delay(200)
                assertEquals(newer.canonicalPath, browser.state.value.current!!.path)
                assertEquals("", browser.state.value.error)
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 45000) fun longListingsArePagedAndNavigationResetsTheLimit() = runBlocking {
        fixture { own, scope ->
            val volume = File(own, "volume")
            withContext(Dispatchers.IO) {
                check(volume.mkdirs())
                for (index in 0..200) check(File(volume, "project-%03d".format(index)).mkdir())
            }
            val unavailable = AtomicBoolean(false)
            val browser = LocalDirectoryBrowser(context, scope) {
                if (unavailable.get()) throw SecurityException("Fixture volume permission revoked")
                listOf(volume)
            }
            main { browser.open() }; await { !browser.state.value.loading }
            val state = browser.state.value
            assertEquals("", state.error); assertTrue(state.truncated)
            assertEquals(200, state.directories.size)
            assertEquals("project-000", state.directories.first().name)
            assertEquals("project-199", state.directories.last().name)
            unavailable.set(true)
            main { browser.more() }; await { !browser.state.value.loading }
            assertEquals(volume.canonicalPath, browser.state.value.current!!.path)
            assertEquals(200, browser.state.value.directories.size)
            assertTrue(browser.state.value.error.isNotEmpty())
            assertFalse(browser.state.value.selectable)
            unavailable.set(false)
            main { browser.more() }; await { !browser.state.value.loading }
            assertEquals(201, browser.state.value.directories.size)
            assertEquals("project-200", browser.state.value.directories.last().name)
            assertFalse(browser.state.value.truncated)
            main { browser.open(File(volume, "project-200").path) }; await { !browser.state.value.loading }
            assertEquals("project-200", browser.state.value.current!!.name)
            main { browser.open(volume.path) }; await { !browser.state.value.loading }
            assertEquals(200, browser.state.value.directories.size)
            assertTrue(browser.state.value.truncated)
        }
    }
}
