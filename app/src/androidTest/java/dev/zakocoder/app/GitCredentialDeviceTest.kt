package dev.zakocoder.app

import android.content.ContextWrapper
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.git.*
import dev.zakocoder.app.workflow.shellArgument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class GitCredentialDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val project = "e56217d3-a9b8-4ace-8972-3056ebbbec22"
    private val remote = "https://example.invalid/owner/repository.git"
    private fun fixture(block: (File, GitCredentialStore) -> Unit) {
        val directory = File(context.cacheDir, "git-auth-test-${UUID.randomUUID()}").apply { mkdirs() }
        val wrapper = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        try { block(directory, GitCredentialStore(wrapper)) } finally { directory.deleteRecursively() }
    }
    @Test fun encryptedStoreBindsProjectOriginPortAndRepositoryAndCanRevoke() = fixture { directory, store ->
        store.save(project, remote, "fixture-user", "test-only-token-placeholder")
        assertEquals("fixture-user", store.summary(project, remote)?.username)
        assertEquals("test-only-token-placeholder", store.credential(project, remote)?.token)
        val bytes = File(directory, "git-credentials-v1.json").readText()
        assertFalse(bytes.contains("test-only-token-placeholder")); assertFalse(bytes.contains("fixture-user"))
        assertFalse(bytes.contains("example.invalid"))
        assertNull(store.summary(UUID.randomUUID().toString(), remote))
        assertNull(store.summary(project, "https://different.invalid/owner/repository.git"))
        assertNull(store.summary(project, "https://example.invalid:8443/owner/repository.git"))
        assertNull(store.summary(project, "https://example.invalid/other/repository.git"))
        assertEquals("fixture-user", store.summary(project, "https://example.invalid:443/owner/repository.git")?.username)
        store.remove(project, remote); assertNull(store.summary(project, remote))
    }
    @Test fun removingProjectRevokesEveryOriginAndPreservesOtherProjects() = fixture { _, store ->
        val other = UUID.randomUUID().toString()
        val secondRemote = "https://second.invalid/owner/repo.git"
        store.save(project, remote, "fixture", "fixture-first-token")
        store.save(project, secondRemote, "fixture", "fixture-second-token")
        store.save(other, remote, "fixture", "fixture-other-token")
        store.removeProject(project)
        assertNull(store.summary(project, remote))
        assertNull(store.summary(project, secondRemote))
        assertEquals("fixture", store.summary(other, remote)?.username)
        store.removeProject(project) // Idempotent even when already absent.
    }
    @Test fun startupCleanupRemovesKilledLegacyLeaseWithoutFollowingLinksAndKeepsActiveLease() = fixture { directory, store ->
        val root = File(directory, "rootfs").apply { mkdirs() }
        val parent = File(root, ".zakocoder-git-auth").apply { mkdirs() }
        val killed = File(parent, "operation-killed").apply { mkdirs() }
        File(killed, "token").writeText("synthetic-old-version-token")
        File(killed, "askpass").writeText("synthetic helper")
        val outside = File(directory, "outside").apply { mkdirs() }
        val untouched = File(outside, "token").apply { writeText("synthetic-unrelated-data") }
        Files.createSymbolicLink(File(parent, "operation-link").toPath(), outside.toPath())
        runBlocking {
            GitCredentialAccess.prepareRuntime(root)
            assertFalse(killed.exists())
            assertFalse(Files.exists(File(parent, "operation-link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("synthetic-unrelated-data", untouched.readText())
            withTimeout(3000) {
                GitCredentialAccess(store, root).withLease(GitCredential("fixture", "fixture-token"), remote) { guest ->
                    val helper = File(root, guest.removePrefix("/"))
                    GitCredentialAccess.prepareRuntime(root)
                    assertTrue(helper.exists())
                    assertTrue(OsConstants.S_ISFIFO(Os.stat(File(helper.parentFile, "token").absolutePath).st_mode))
                    // No password prompt: writer must stop promptly when the operation returns.
                }
            }
            assertTrue(parent.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun fifoDeliversRepeatedLargePromptsWithoutPersistingToken() = fixture { directory, store ->
        val root = File(directory, "rootfs").apply { mkdirs() }
        val syntheticToken = "x".repeat(8192)
        runBlocking {
            withTimeout(5000) {
                GitCredentialAccess(store, root).withLease(GitCredential("fixture", syntheticToken), remote) { guest ->
                    val helper = File(root, guest.removePrefix("/"))
                    val fifo = File(helper.parentFile, "token")
                    repeat(2) {
                        val descriptor = Os.open(fifo.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                        val received = java.io.ByteArrayOutputStream()
                        try {
                            val buffer = ByteArray(1024)
                            while (received.size() < syntheticToken.length + 1) {
                                val count = try { Os.read(descriptor, buffer, 0, buffer.size) }
                                catch (error: ErrnoException) {
                                    if (error.errno != OsConstants.EAGAIN) throw error
                                    0
                                }
                                if (count > 0) received.write(buffer, 0, count) else delay(5)
                            }
                        } finally { Os.close(descriptor) }
                        assertTrue("FIFO must deliver the complete synthetic credential", received.toString("UTF-8") == syntheticToken + "\n")
                    }
                    assertTrue(OsConstants.S_ISFIFO(Os.stat(fifo.absolutePath).st_mode))
                    assertFalse(helper.readText().contains(syntheticToken))
                }
            }
            assertTrue(File(root, ".zakocoder-git-auth").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun streamRedactionCoversSecretsSplitAcrossArbitraryChunks() {
        val redactor = GitCredentialStreamRedactor(listOf("fixture-token", "abcabc"))
        assertEquals("fetch ", redactor.accept("fetch fix"))
        assertEquals("[redacted] done", redactor.accept("ture-token done"))
        assertEquals("", redactor.accept("abc"))
        assertEquals("[redacted]", redactor.accept("abc"))
        assertEquals("more", redactor.accept("more"))
    }
    @Test fun helperPromptPathMatchesGitCredentialUrlEscaping() {
        assertEquals("owner/a%20b.git", gitCredentialPromptPath("owner/a b.git"))
        assertEquals("a%27b.git", gitCredentialPromptPath("a'b.git"))
        assertEquals("a%3Ab%40c%21d%24e%26f%28g%29%2Bh%2Ci%3Bj%3Dk~l.git", gitCredentialPromptPath("a:b@c!d$" + "e&f(g)+h,i;j=k~l.git"))
    }
    @Test fun malformedAndCredentialBearingUrlsFailClosed() {
        listOf("http://example.invalid/repo", "https://user:password@example.invalid/repo", "https://example.invalid/repo?q=1",
            "https://example.invalid/repo#fragment", "https://example.invalid/a/../repo", "file:///tmp/repo").forEach {
            try { gitCredentialBinding(project, it); fail("Unsafe binding must fail") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("GitCredential(<redacted>)", GitCredential("fixture", "test-token").toString())
        listOf("has space", "newline\n", "").forEach { value ->
            try { GitCredential("fixture", value).validate(); fail("Unsafe token must fail") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun shortLivedHelperUsesPrivateFilesAndCleansOnCancellation() = fixture { directory, store ->
        val root = File(directory, "rootfs").apply { mkdirs() }
        runBlocking {
            val access = GitCredentialAccess(store, root)
            try {
                access.withLease(GitCredential("fixture-user", "test-only-token-placeholder"), remote) { guest ->
                    val helper = File(root, guest.removePrefix("/"))
                    val token = File(helper.parentFile, "token")
                    assertTrue(helper.isFile); assertEquals(448, Os.stat(helper.absolutePath).st_mode and 511)
                    assertEquals(384, Os.stat(token.absolutePath).st_mode and 511)
                    assertTrue(OsConstants.S_ISFIFO(Os.stat(token.absolutePath).st_mode))
                    assertFalse(helper.readText().contains("test-only-token-placeholder"))
                    assertTrue(helper.readText().contains(shellArgument("Username for 'https://example.invalid/owner/repository.git': ")))
                    throw CancellationException("fixture cancellation")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(File(root, ".zakocoder-git-auth").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun symlinkedAuthenticationParentIsRejectedWithoutWritingOutsideRoot() = fixture { directory, store ->
        val root = File(directory, "rootfs").apply { mkdirs() }
        val outside = File(directory, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(root, ".zakocoder-git-auth").toPath(), outside.toPath())
        runBlocking {
            try { GitCredentialAccess(store, root).withLease(GitCredential("fixture", "test-token"), remote) { fail("Callback must not run") }; fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }
    @Test fun executorKeepsTokenOutOfShellAndRedactsReturnedOutput() = fixture { directory, store ->
        val root = File(directory, "rootfs").apply { mkdirs() }
        store.save(project, remote, "fixture-user", "test-only-token-placeholder")
        runBlocking {
            val result = GitCredentialAccess(store, root).execute(project, "fixture-git-command", remote) { id, script ->
                assertEquals(project, id); assertFalse(script.contains("test-only-token-placeholder"))
                assertTrue(script.contains("GIT_ASKPASS=")); assertTrue(script.endsWith("fixture-git-command"))
                dev.zakocoder.app.runtime.CommandResult(1, "failure test-only-token-placeholder")
            }
            assertEquals("failure [redacted]", result.output)
            assertTrue(File(root, ".zakocoder-git-auth").listFiles().orEmpty().isEmpty())
        }
    }
}
