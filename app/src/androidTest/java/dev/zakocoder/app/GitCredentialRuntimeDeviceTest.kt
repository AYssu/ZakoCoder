package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.git.GitCredentialAccess
import dev.zakocoder.app.git.GitCredentialStore
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.workflow.shellArgument
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.UUID

/** Real Ubuntu Git asks the actual short-lived helper. No network and no user Git/model credentials. */
class GitCredentialRuntimeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(block: () -> Boolean) = withTimeout(30000) { while (!block()) delay(50) }

    @Test(timeout = 120000)
    fun actualGitCredentialFillMatchesEscapedRepositoryPromptsAndCleansEveryLease() = runBlocking {
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await { !workbench.state.value.loading && !workbench.state.value.busy }
        assumeTrue("Requires the existing Ubuntu environment", workbench.state.value.installed)
        check(!workbench.ai.state.value.active && !workbench.git.state.value.busy && !workbench.projectTasks.state.value.busy)
        val id = UUID.randomUUID().toString()
        val fixture = File(context.cacheDir, "git-credential-runtime-${UUID.randomUUID()}")
        val directory = File(workbench.runtime.workspaces, id)
        val wrapper = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = fixture }
        val store = GitCredentialStore(wrapper)
        val access = GitCredentialAccess(store, workbench.runtime.root)
        val username = "fixture-user_1.test"
        val token = "fixture-runtime-token-placeholder"
        withContext(Dispatchers.IO) { check(fixture.mkdir()); check(directory.mkdir()) }
        try {
            val probe = workbench.runtime.execute("command -v git >/dev/null", id, workbench.state.value.settings, timeoutMillis = 10000)
            assumeTrue("Requires Git installed in the existing Ubuntu environment", probe.exitCode == 0)
            val urls = listOf(
                "https://example.invalid/team/repository.git",
                "https://example.invalid/team/with%20space.git",
                "https://example.invalid/team/hash%23part.git",
                "https://example.invalid/team/a%2Fb.git",
                "https://example.invalid/team/a%27b%25literal.git",
                "https://example.invalid:443/team/a%3Ab%40c%21d.git",
            )
            for (remote in urls) {
                val uri = URI(remote)
                withContext(Dispatchers.IO) { store.save(id, remote, username, token) }
                val input = "protocol=https\nhost=${uri.rawAuthority}\npath=${uri.path.removePrefix("/")}\n\n"
                val script = "printf '%s' ${shellArgument(input)} | " +
                    "env GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 " +
                    "GIT_TRACE=0 GIT_TRACE_PACKET=0 GIT_TRACE_CURL=0 GIT_CURL_VERBOSE=0 " +
                    "git -c credential.helper= -c credential.useHttpPath=true -c core.hooksPath=/dev/null credential fill"
                val leases = mutableListOf<File>()
                val sanitized = access.execute(id, script, remote) { project, guarded ->
                    assertFalse("Credential must never enter process shell source", guarded.contains(token))
                    val guestHelper = Regex("/\\.zakocoder-git-auth/operation-[A-Za-z0-9_-]+/askpass").find(guarded)?.value
                    requireNotNull(guestHelper) { "Expected the operation's own ASKPASS path" }
                    leases += File(workbench.runtime.root, guestHelper.removePrefix("/")).parentFile!!
                    val actual = workbench.runtime.execute(guarded, project, workbench.state.value.settings, timeoutMillis = 15000)
                    // Do not use assertEquals on secret-bearing output: JUnit failures would print it.
                    assertTrue("Actual Git credential fill must succeed for the approved repository", actual.exitCode == 0)
                    assertTrue("Git must receive the expected fixture username", actual.output.lineSequence().any { it == "username=$username" })
                    assertTrue("Git must receive the expected fixture token", actual.output.lineSequence().any { it == "password=$token" })
                    actual
                }
                assertFalse("Returned transcript must redact credentials", sanitized.output.contains(token))
                assertTrue("Fixture token must be replaced in returned output", "password=[redacted]" in sanitized.output)
                assertTrue("Every temporary helper lease must be cleaned", leases.isNotEmpty() && leases.all { !it.exists() })
                withContext(Dispatchers.IO) { store.remove(id, remote) }
            }
            // The helper must not answer a prompt for another host or repository even within its lease.
            val remote = "https://example.invalid/team/authorized.git"
            withContext(Dispatchers.IO) { store.save(id, remote, username, token) }
            for (mismatch in listOf("host=other.example.invalid\npath=team/authorized.git", "host=example.invalid\npath=team/other.git")) {
                val input = "protocol=https\n$mismatch\n\n"
                val script = "printf '%s' ${shellArgument(input)} | " +
                    "env GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 " +
                    "git -c credential.helper= -c credential.useHttpPath=true credential fill"
                access.execute(id, script, remote) { project, guarded ->
                    val actual = workbench.runtime.execute(guarded, project, workbench.state.value.settings, timeoutMillis = 15000)
                    assertTrue("Mismatched prompt must fail closed", actual.exitCode != 0)
                    assertFalse("Mismatched prompt must never obtain the token", actual.output.contains(token))
                    actual
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // Unique unregistered fixture project and fixture credential catalog only.
                PrivateFiles.deleteTree(directory)
                PrivateFiles.deleteTree(fixture)
            }
        }
    }
}
