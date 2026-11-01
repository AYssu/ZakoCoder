package dev.zakocoder.app

import dev.zakocoder.app.runtime.DevelopmentTools
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs generated install shell against private fake commands; never invokes host APT/alternatives. */
class JavaAlternativesInstallTest {
    @Test fun installationRestoresExistingSelectionWithSpacesAndShellSyntax() {
        fixture().use { f ->
            val previous = "/jdk 17/\$(printf unsafe)/bin/java"
            f.state.writeText(previous)
            assertEquals(0, f.run())
            assertEquals(previous, f.state.readText())
            assertFalse(File(f.root, "unsafe").exists())
        }
    }

    @Test fun aptFailureStillRestoresPreviousSelectionAndFails() {
        fixture().use { f ->
            f.state.writeText("/jdk17/bin/java")
            assertEquals(42, f.run(aptExit = "42"))
            assertEquals("/jdk17/bin/java", f.state.readText())
        }
    }

    @Test fun restorationMustBeVerifiedBeforeSuccess() {
        fixture().use { f ->
            f.state.writeText("/jdk17/bin/java")
            assertNotEquals(0, f.run(ignoreSet = "1"))
            assertEquals("/jdk21/bin/java", f.state.readText())
        }
    }

    @Test fun withoutExistingJavaNewJdkCanBecomeDefault() {
        fixture().use { f ->
            assertEquals(0, f.run())
            assertEquals("/jdk21/bin/java", f.state.readText())
        }
    }

    @Test fun unrelatedPackageInstallationDoesNotChangeAlternatives() {
        assertFalse(DevelopmentTools.installScript(listOf("git")).contains("update-alternatives"))
    }

    private fun fixture() = Fixture()
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("zako-java-alternatives-").toFile()
        val state = File(root, "selection")
        private val bin = File(root, "bin").apply { mkdirs() }
        init {
            command("apt-get", """
                #!/bin/sh
                if [ "${'$'}1" = install ]; then
                    printf '%s' /jdk21/bin/java > "${'$'}ZAKO_TEST_SELECTION"
                    exit "${'$'}ZAKO_TEST_APT_EXIT"
                fi
            """)
            command("update-alternatives", """
                #!/bin/sh
                case "${'$'}1" in
                  --query)
                    [ "${'$'}2" = java ] && [ -f "${'$'}ZAKO_TEST_SELECTION" ] || exit 2
                    printf 'Name: java\nValue: %s\nAlternative: %s\n' "${'$'}(cat "${'$'}ZAKO_TEST_SELECTION")" "${'$'}(cat "${'$'}ZAKO_TEST_SELECTION")"
                    ;;
                  --set)
                    [ "${'$'}ZAKO_TEST_IGNORE_SET" != 1 ] || exit 0
                    printf '%s' "${'$'}3" > "${'$'}ZAKO_TEST_SELECTION"
                    ;;
                  *) exit 2 ;;
                esac
            """)
        }
        private fun command(name: String, script: String) {
            File(bin, name).apply { writeText(script.trimIndent() + "\n"); check(setExecutable(true)) }
        }
        fun run(aptExit: String = "0", ignoreSet: String = "0"): Int {
            val script = File(root, "install.sh").apply {
                writeText(DevelopmentTools.installScript(listOf("openjdk-21-jdk-headless")))
            }
            val process = ProcessBuilder("/bin/sh", script.absolutePath).directory(root).redirectErrorStream(true)
            process.environment().apply {
                put("PATH", bin.absolutePath + ":/usr/bin:/bin")
                put("ZAKO_TEST_SELECTION", state.absolutePath)
                put("ZAKO_TEST_APT_EXIT", aptExit)
                put("ZAKO_TEST_IGNORE_SET", ignoreSet)
                put("TMPDIR", root.absolutePath)
            }
            val running = process.start()
            val output = running.inputStream.bufferedReader().readText()
            val status = running.waitFor()
            assertFalse("Temporary choice file leaked: $output", root.listFiles().orEmpty().any { it.name.startsWith("tmp.") })
            return status
        }
        override fun close() { root.deleteRecursively() }
    }
}
