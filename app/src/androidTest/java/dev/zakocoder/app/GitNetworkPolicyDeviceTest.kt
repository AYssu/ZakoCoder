package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.git.*
import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Controller integration only: synthetic repository metadata; no guest process or network. */
class GitNetworkPolicyDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun fetchAndPushRejectBothRewriteKindsBeforeNetworkAndNeverRewriteConfiguration() {
        val fixture = File(instrumentation.targetContext.cacheDir, "git-network-policy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val configuration = File(fixture, "config").apply {
            writeText("[remote \"origin\"]\n url = https://approved.example.invalid/repo.git\n")
        }
        val original = configuration.readBytes()
        val commands = mutableListOf<String>()
        var probeOutput = ""
        var probeCalls = 0
        var networkCalls = 0
        val controller = ProjectGit(instrumentation.targetContext, { _, script ->
            commands += script
            when {
                "config --get-regexp" in script -> { probeCalls++; CommandResult(0, probeOutput) }
                "status --porcelain" in script -> CommandResult(0, "# branch.head work\u0000# branch.upstream origin/work\u0000# branch.ab +1 -1\u0000")
                "for-each-ref" in script -> CommandResult(0, "work\n")
                script.endsWith(" remote") -> CommandResult(0, "origin\n")
                "remote get-url" in script -> CommandResult(0, "https://approved.example.invalid/repo.git\n")
                "MERGE_HEAD" in script -> CommandResult(1, "")
                "rev-parse --verify" in script -> CommandResult(0, "a".repeat(40) + "\n")
                else -> error("Unexpected controller command in a no-network policy fixture")
            }
        }, { true }, { _, _, _ -> networkCalls++; error("Blocked rewrite must never reach network callback") })
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitBlocked() = runBlocking { withTimeout(10000) { controller.state.first { !it.busy && it.failure == GitFailure.UrlRewrite } } }
        try {
            main { controller.select("network-policy-fixture") }
            runBlocking { withTimeout(10000) { controller.state.first { !it.busy && it.statusKnown } } }
            for (kind in listOf("insteadof", "pushinsteadof")) {
                probeOutput = "url.https://hidden.example.invalid/repo.git.$kind https://approved.example.invalid/repo.git\n"
                main { controller.fetch() }
                val fetched = awaitBlocked()
                assertEquals(GitFailure.UrlRewrite, fetched.failure)
                assertTrue(fetched.statusKnown)
                main { controller.push() }
                val pushed = awaitBlocked()
                assertEquals(GitFailure.UrlRewrite, pushed.failure)
                assertTrue(pushed.statusKnown)
            }
            assertEquals(4, probeCalls)
            assertEquals(0, networkCalls)
            assertArrayEquals("Repository configuration must be preserved", original, configuration.readBytes())
            assertFalse("No config mutation command is permitted", commands.any { " config " in it && "config --get-regexp" !in it })
            assertFalse("Neither network operation may bypass the policy via the plain executor", commands.any { " fetch " in it || " push " in it })
            assertFalse("Rejected rewrite destinations are not copied into user-visible output", controller.state.value.output.contains("hidden.example.invalid"))
        } finally { main { controller.stop() }; fixture.deleteRecursively() }
    }
}
