package dev.zakocoder.app.editor.lsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

/** Executes the actual installer with an owned temporary prefix and an offline failing curl. */
class LspInstallerSafetyTest {
    private class Fixture(val root: Path) {
        val prefix: Path = root.resolve("lsp")
        val base: Path = prefix.resolve("java")
        val version: String = LspServer.Java.installScript.lineSequence()
            .first { it.trimStart().startsWith("target=") }.substringAfter("\$base/").substringBefore('"')
        val target: Path = base.resolve(version)
        init {
            Files.createDirectories(base)
            val bin = Files.createDirectory(root.resolve("bin"))
            Files.writeString(bin.resolve("curl"), "#!/bin/sh\nexit 42\n")
            check(bin.resolve("curl").toFile().setExecutable(true))
        }
        fun installed() {
            Files.createDirectory(target)
            Files.writeString(target.resolve(".zakocoder-verified"), "$version\n")
        }
        fun stage(name: String, owned: Boolean): Path = Files.createDirectory(base.resolve(".stage.$name")).also {
            if (owned) Files.writeString(it.resolve(".zakocoder-stage"), "lsp-stage-v1\n")
        }
        fun run(): Pair<Int, String> {
            val script = LspServer.Java.installScript.replace("/opt/zakocoder/lsp", prefix.toString())
            val scriptFile = root.resolve("install.sh")
            Files.writeString(scriptFile, script)
            val output = root.resolve("process.log").toFile()
            val builder = ProcessBuilder("/bin/sh", scriptFile.toString()).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(output)
            builder.environment()["PATH"] = "${root.resolve("bin")}:/usr/bin:/bin"
            val process = builder.start()
            try {
                assertTrue("Controlled installer exceeded 10 seconds", process.waitFor(10, TimeUnit.SECONDS))
                return process.exitValue() to output.readText()
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("zako-lsp-installer-test-")
        try { block(Fixture(root)) }
        finally {
            // Files.walk does not follow links; cleanup also preserves targets outside this root.
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun symlinkLockIsRejectedWithoutTruncatingItsTarget() = fixture { env ->
        val sentinel = env.root.resolve("sentinel")
        Files.writeString(sentinel, "unchanged lock target\n")
        Files.createSymbolicLink(env.base.resolve(".install.lock"), sentinel)
        val (exit, output) = env.run()
        assertTrue("Symlink lock must fail: $output", exit != 0)
        assertEquals("unchanged lock target\n", Files.readString(sentinel))
        assertFalse(Files.exists(env.target))
        Files.list(env.base).use { entries -> assertFalse(entries.anyMatch { it.fileName.toString().startsWith(".stage.") }) }
    }

    @Test fun failedDownloadRemovesOwnedStageAndNeverPublishesTarget() = fixture { env ->
        val (exit, output) = env.run()
        assertEquals("Must invoke the offline curl stub: $output", 42, exit)
        assertFalse(Files.exists(env.target))
        Files.list(env.base).use { entries -> assertFalse(entries.anyMatch { it.fileName.toString().startsWith(".stage.") }) }
    }

    @Test fun verifiedReentryCleansOnlyMarkedStagesBeforeReturningSuccess() = fixture { env ->
        env.installed()
        val owned = env.stage("owned", owned = true)
        Files.writeString(owned.resolve("interrupted-download"), "partial")
        val unmarked = env.stage("unmarked", owned = false)
        Files.writeString(unmarked.resolve("user-file"), "preserve")
        val (exit, output) = env.run()
        assertEquals("Verified installer should return without downloading: $output", 0, exit)
        assertFalse(Files.exists(owned))
        assertEquals("preserve", Files.readString(unmarked.resolve("user-file")))
        assertEquals("${env.version}\n", Files.readString(env.target.resolve(".zakocoder-verified")))
    }

    @Test fun recoveryDoesNotFollowStageSymlinksOrSymlinkedMarkers() = fixture { env ->
        env.installed()
        val sentinelDirectory = Files.createDirectory(env.root.resolve("outside-stage"))
        val sentinel = sentinelDirectory.resolve("sentinel")
        Files.writeString(sentinel, "outside data survives")
        val owned = env.stage("owned", owned = true)
        Files.createSymbolicLink(owned.resolve("external-directory"), sentinelDirectory)
        Files.createSymbolicLink(owned.resolve("external-file"), sentinel)
        val wholeStageLink = env.base.resolve(".stage.link")
        Files.createSymbolicLink(wholeStageLink, sentinelDirectory)
        val markerLinkStage = env.stage("linked-marker", owned = false)
        val externalMarker = env.root.resolve("external-marker")
        Files.writeString(externalMarker, "lsp-stage-v1\n")
        Files.createSymbolicLink(markerLinkStage.resolve(".zakocoder-stage"), externalMarker)
        val (exit, output) = env.run()
        assertEquals(output, 0, exit)
        assertFalse(Files.exists(owned))
        assertEquals("outside data survives", Files.readString(sentinel))
        assertTrue(Files.isSymbolicLink(wholeStageLink))
        assertTrue(Files.isDirectory(markerLinkStage))
        assertEquals("lsp-stage-v1\n", Files.readString(externalMarker))
    }
}
