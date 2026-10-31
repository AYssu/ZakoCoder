package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiLogRedactor
import dev.zakocoder.app.git.GIT_PREFIX
import dev.zakocoder.app.git.GitCredentialAccess
import dev.zakocoder.app.git.GitCredentialStore
import dev.zakocoder.app.git.ProjectGit
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in public fetch only. Uses no saved user credentials, pushes, registered projects or paid AI. */
class GitPublicNetworkDeviceTest {
    @Test(timeout = 300000)
    fun publicFetchStreamsProgressAndCreatesRemoteRefs(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit network verification required", InstrumentationRegistry.getArguments().getString("verifyPublicGitNetwork") == "true")
        val context = instrumentation.targetContext
        val workbench = (context.applicationContext as ZakoApplication).workbench
        withTimeout(30000) { workbench.state.first { !it.loading && !it.busy } }
        assumeTrue("Requires an installed Ubuntu environment", workbench.state.value.installed)
        check(!workbench.ai.state.value.active && !workbench.git.state.value.busy && !workbench.projectTasks.state.value.busy)
        val id = UUID.randomUUID().toString()
        val directory = File(workbench.runtime.workspaces, id)
        val fixture = File(context.cacheDir, "git-public-network-$id")
        val wrapper = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = fixture }
        val access = GitCredentialAccess(GitCredentialStore(wrapper), workbench.runtime.root)
        val chunks = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val evidence = JSONObject().put("project", id).put("remote", "https://github.com/octocat/Hello-World.git")
            .put("note", "Non-TTY fetch requires explicit --progress; callbacks are actual runtime chunks")
        lateinit var git: ProjectGit
        git = ProjectGit(context, { project, script ->
            commands += script
            workbench.runtime.execute(script, project, workbench.state.value.settings, timeoutMillis = 30000)
        }, { true }, { project, script, remote ->
            commands += script
            check(" fetch --progress " in script)
            access.execute(project, script, remote) { target, guarded, redact ->
                check("GIT_ASKPASS=/bin/false" in guarded)
                var streamed = ""
                workbench.runtime.execute(guarded, target, workbench.state.value.settings, timeoutMillis = 180000,
                    output = { chunk ->
                        val safe = redact(chunk)
                        chunks += safe
                        streamed = (streamed + safe).takeLast(24000)
                        git.reportOutput(streamed)
                    })
            }
        })
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun settled() = withTimeout(210000) { git.state.first { !it.busy } }
        withContext(Dispatchers.IO) { check(directory.mkdir()); check(fixture.mkdir()) }
        try {
            main { git.select(id) }
            settled()
            main { git.initialize() }
            val initialized = settled()
            check(initialized.repository && initialized.failure == null) { "Git initialization failed" }
            val configure = workbench.runtime.execute("$GIT_PREFIX remote add origin https://github.com/octocat/Hello-World.git", id,
                workbench.state.value.settings, timeoutMillis = 10000)
            check(configure.exitCode == 0) { "Could not configure isolated public fixture origin" }
            main { git.refresh() }
            check(settled().remotes.single().url == "https://github.com/octocat/Hello-World.git")
            main { git.fetch() }
            val fetched = settled()
            evidence.put("failure", fetched.failure?.name ?: JSONObject.NULL)
                .put("streamChunkCount", chunks.size).put("streamChars", chunks.sumOf { it.length })
                .put("stream", AiLogRedactor("").clean(chunks.joinToString("")))
                .put("output", AiLogRedactor("").clean(fetched.output))
            assertNull("Public fetch must succeed", fetched.failure)
            assertTrue("Runtime must publish live output chunks", chunks.any { it.isNotBlank() })
            assertTrue("Fetch command must explicitly request non-TTY progress", commands.any { " fetch --progress " in it })
            assertTrue("Live progress must be visible in controller output", fetched.output.isNotBlank())
            assertTrue("Actual Git progress must arrive through streaming callbacks", chunks.joinToString("").let {
                "Receiving objects" in it || "remote:" in it || "Resolving deltas" in it
            })
            val refs = workbench.runtime.execute("$GIT_PREFIX for-each-ref --format='%(refname)' refs/remotes/origin/", id,
                workbench.state.value.settings, timeoutMillis = 10000)
            assertEquals(0, refs.exitCode)
            assertTrue("Fetch must create real remote tracking refs", refs.output.lineSequence().any { it.startsWith("refs/remotes/origin/") })
            evidence.put("refs", refs.output).put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("passed", false).put("errorType", failure.javaClass.simpleName)
            throw failure
        } finally {
            main { git.stop() }
            withContext(NonCancellable + Dispatchers.IO) {
                withTimeout(15000) { git.state.first { !it.busy } }
                PrivateFiles.deleteTree(directory)
                PrivateFiles.deleteTree(fixture)
                evidence.put("fixtureRemoved", !directory.exists() && !fixture.exists())
                File(context.cacheDir, "git-public-network-result.json").writeText(evidence.toString(2))
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("gitPublicNetworkEvidence", "cache/git-public-network-result.json")
                putString("gitPublicNetworkProject", id)
            })
        }
    }
}
