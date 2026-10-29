package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.SettingsRepository
import dev.zakocoder.app.settings.TerminalShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TerminalPromptDeviceTest {
    @Test fun defaultFontIsElevenAndExistingPreferenceIsPreserved() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "terminal-font-fixture-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val repository = SettingsRepository(isolated)
            assertEquals(11, repository.load().terminalFontSp)
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putInt("font", 13).commit()
            assertEquals(13, repository.load().terminalFontSp)
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test(timeout = 60000) fun spacedChineseProjectPromptAndUserOverrideWorkInRealZsh() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LinuxRuntime(base)
        val id = UUID.randomUUID().toString()
        val directory = File(runtime.workspaces, id)
        val fixture = File(base.cacheDir, "terminal-prompt-$id")
        val context = object : ContextWrapper(base) { override fun getFilesDir() = fixture }
        val managed = LinuxRuntime(context)
        try {
            withContext(Dispatchers.IO) {
                assertTrue(File(runtime.root, "usr/bin/zsh").isFile)
                check(directory.mkdirs())
                File(managed.root, "usr/bin").mkdirs()
                File(managed.root, "usr/bin/zsh").writeText("fixture")
                managed.prepareTerminal(AppSettings(terminalShell = TerminalShell.Zsh))
                val source = File(managed.root, "etc/zakocoder/zsh/.zshrc").readText()
                    .replace("/root/.cache/zakocoder", "/workspace/$id/.cache")
                    .replace("/root/.zshrc", "/workspace/$id/user.zshrc")
                File(directory, ".cache").mkdir()
                // This is a prompt compatibility test, not a cold completion-database benchmark.
                val dump = File(runtime.root, "root/.cache/zakocoder/zcompdump")
                if (dump.isFile && !java.nio.file.Files.isSymbolicLink(dump.toPath())) {
                    dump.copyTo(File(directory, ".cache/zcompdump"))
                }
                File(directory, "managed.zshrc").writeText(source)
                File(directory, "subdir").mkdir()
            }
            val command = """
                export ZAKOCODER_PROJECT_PATH=/workspace/$id
                export ZAKOCODER_PROJECT_LABEL='123 测试'
                /usr/bin/zsh -f -c 'source ./managed.zshrc; _zakocoder_prompt; print -P -- "${'$'}PROMPT"; cd subdir; _zakocoder_prompt; print -P -- "${'$'}PROMPT"'
            """.trimIndent()
            val result = runtime.execute(command, id, AppSettings(), timeoutMillis = 20000)
            assertEquals(result.output, 0, result.exitCode)
            assertTrue(result.output, result.output.contains("123 测试"))
            assertTrue(result.output, result.output.contains("subdir"))
            assertFalse(result.output, result.output.contains("invalid character"))
            withContext(Dispatchers.IO) { File(directory, "user.zshrc").writeText("PROMPT='USER_CUSTOM_PROMPT'\n") }
            val custom = runtime.execute("/usr/bin/zsh -f -c 'source ./managed.zshrc; print -P -- \"${'$'}PROMPT\"; (( ${'$'}+functions[_zakocoder_prompt] == 0 ))'", id, AppSettings(), timeoutMillis = 20000)
            assertEquals(custom.output, 0, custom.exitCode)
            assertTrue(custom.output, custom.output.contains("USER_CUSTOM_PROMPT"))
            withContext(Dispatchers.IO) { File(directory, "user.zshrc").writeText("precmd() { PROMPT='USER_DYNAMIC_PROMPT'; }\n") }
            val dynamic = runtime.execute("/usr/bin/zsh -f -c 'source ./managed.zshrc; precmd; _zakocoder_prompt; print -P -- \"${'$'}PROMPT\"'", id, AppSettings(), timeoutMillis = 20000)
            assertEquals(dynamic.output, 0, dynamic.exitCode)
            assertTrue(dynamic.output, dynamic.output.contains("USER_DYNAMIC_PROMPT"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(directory); PrivateFiles.deleteTree(fixture) }
        }
    }
    @Test(timeout = 60000) fun firstTabInitializesCompletionAndCompletesThePendingInput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LinuxRuntime(context)
        val manager = dev.zakocoder.app.terminal.TerminalManager(context) { }
        val id = UUID.randomUUID().toString()
        val root = File(runtime.workspaces, id)
        val suffix = id.replace("-", "")
        val marker = "ZAKO_COMPLETION_$suffix"
        try {
            withContext(Dispatchers.IO) {
                check(root.mkdirs())
                File(root, "completion-check.txt").writeText(marker + "\n")
                manager.prepareTypeface()
                runtime.prepareTerminal(AppSettings(terminalShell = TerminalShell.Zsh))
            }
            val command = runtime.command(id, AppSettings(terminalShell = TerminalShell.Zsh), null, projectName = "123 测试")
            val item = withContext(Dispatchers.Main) { manager.create(id, "123 测试", command) }
            withContext(Dispatchers.Main) { item.session.write("cat completion-\t\n") }
            withTimeout(20000) {
                while (!withContext(Dispatchers.Main) {
                    assertTrue(item.session.isRunning)
                    item.session.emulator.screen.transcriptText.contains(marker)
                }) delay(25)
            }
            val text = withContext(Dispatchers.Main) { item.session.emulator.screen.transcriptText }
            assertFalse("Spaced names must not cause named-directory errors", text.contains("invalid character"))
            assertFalse("Completion must not invoke an unknown command", text.contains("command not found"))
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { manager.closeAll() }
            withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) }
        }
    }

}
