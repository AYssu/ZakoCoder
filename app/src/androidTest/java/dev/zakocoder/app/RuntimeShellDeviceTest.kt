package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.TerminalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class RuntimeShellDeviceTest {
    @Test fun linkDepotIsCanonicalPersistentAndRefusesExistingSymlink(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(base.cacheDir, "depot-check-${UUID.randomUUID()}")
            val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
            val runtime = LinuxRuntime(context)
            val outside = File(directory, "outside")
            try {
                runtime.root.mkdirs()
                val settings = AppSettings()
                val first = runtime.command(null, settings, "true")
                val depot = File(first.environment.getValue("PROOT_L2S_DIR"))
                assertEquals(File(runtime.root.canonicalFile, ".zakocoder-links"), depot)
                val sentinel = File(depot, "existing-link-data").apply { writeText("preserved") }
                assertEquals(first.environment, runtime.command(null, settings, "true").environment)
                assertEquals("preserved", sentinel.readText())
                PrivateFiles.deleteTree(depot)
                outside.mkdir()
                val protected = File(outside, "protected").apply { writeText("user content") }
                java.nio.file.Files.createSymbolicLink(depot.toPath(), outside.toPath())
                val failure = runCatching { runtime.command(null, settings, "true") }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals("user content", protected.readText())
                assertEquals(listOf("protected"), outside.list()!!.toList())
            } finally { PrivateFiles.deleteTree(directory) }
        }
    }

    @Test fun managedZshConfigPreservesUserFileAndScriptsStayOnBash(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(base.cacheDir, "shell-check-${UUID.randomUUID()}")
            val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
            val runtime = LinuxRuntime(context)
            val settings = AppSettings(terminalShell = TerminalShell.Zsh)
            try {
                assertThrows(IllegalStateException::class.java) { runtime.prepareTerminal(settings) }
                File(runtime.root, "usr/bin").mkdirs()
                File(runtime.root, "usr/bin/zsh").writeText("fixture")
                File(runtime.root, "root").mkdirs()
                val userConfig = File(runtime.root, "root/.zshrc").apply { writeText("# preserved user config\n") }
                runtime.prepareTerminal(settings)
                assertEquals("skip_global_compinit=1\n", File(runtime.root, "etc/zakocoder/zsh/.zshenv").readText())
                assertEquals("# preserved user config\n", userConfig.readText())
                assertTrue(File(runtime.root, "etc/zakocoder/zsh/.zshrc").readText().contains("source /root/.zshrc"))
                val projectId = UUID.randomUUID().toString()
                assertTrue(File(runtime.workspaces, projectId).mkdirs())
                val interactive = runtime.command(projectId, settings, null, projectName = "project;$(invalid)%")
                assertEquals(listOf("/usr/bin/zsh", "-i"), interactive.arguments.takeLast(2))
                assertTrue(interactive.arguments.contains("SHELL=/usr/bin/zsh"))
                assertTrue(interactive.arguments.contains("ZAKOCODER_PROJECT_LABEL=project___invalid__"))
                val script = "printf '%s' 'arbitrary; text'"
                val command = runtime.command(projectId, settings, script)
                assertEquals(listOf("/bin/bash", "--noprofile", "--norc", "-c", script), command.arguments.takeLast(5))
                assertTrue(command.arguments.contains("SHELL=/bin/bash"))
                assertFalse(command.arguments.any { it.startsWith("ZDOTDIR=") })
            } finally { PrivateFiles.deleteTree(directory) }
        }
    }
}
