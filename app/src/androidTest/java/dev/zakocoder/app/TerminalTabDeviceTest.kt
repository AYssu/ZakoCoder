package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.TerminalShell
import dev.zakocoder.app.terminal.TerminalManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Ubuntu/Zsh, PTY, helper Tab and IME. Only UUID fixtures are read or removed. */
class TerminalTabDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var view: TerminalView

    @Test(timeout = 180_000) fun coldCompletionDoesNotBlockTabOrImeAndEventuallyAttaches() = runBlocking {
        exercise(failingWorker = false)
    }

    @Test(timeout = 60_000) fun failedBackgroundCompletionKeepsTabAndImeUsable() = runBlocking {
        exercise(failingWorker = true)
    }

    @Test(timeout = 60_000) fun bashTabAndImeRemainUsableWithAutomaticLsColors() = runBlocking {
        exercise(failingWorker = false, shell = TerminalShell.Bash)
    }

    private suspend fun exercise(failingWorker: Boolean, shell: TerminalShell = TerminalShell.Zsh) {
        warmDeviceActivity()
        val runtime = LinuxRuntime(context)
        val manager = TerminalManager(context) {}
        val id = UUID.randomUUID().toString()
        val root = File(runtime.workspaces, id)
        val fixture = File(context.cacheDir, "terminal-tab-$id")
        val isolated = LinuxRuntime(object : ContextWrapper(context) { override fun getFilesDir() = fixture })
        val guest = "/workspace/$id"
        val settings = AppSettings(terminalShell = shell)
        try {
            withContext(Dispatchers.IO) {
                assertTrue("Preinstalled shell required", File(runtime.root, shell.executable.removePrefix("/")).isFile)
                check(root.mkdirs())
                File(isolated.root, shell.executable.removePrefix("/")).apply { parentFile!!.mkdirs(); writeText("fixture") }
                isolated.prepareTerminal(settings)
                // Exercise the actual managed startup script with a cold, private completion cache.
                // Never source a user's startup file or alter their existing completion cache.
                val startupPath = if (shell == TerminalShell.Zsh) "etc/zakocoder/zsh/.zshrc" else "etc/zakocoder/bash/bashrc"
                var source = File(isolated.root, startupPath).readText()
                    .replace("/root/.cache/zakocoder", "$guest/cache")
                    .replace("/root/.zshrc", "$guest/user.zshrc")
                File(root, "cache").mkdir()
                File(root, "completion-target.txt").writeText("TAB_INPUT_中文_OK\n")
                File(root, "COLOR_DIRECTORY_MARKER").mkdir()
                File(root, ".zshenv").writeText("skip_global_compinit=1\n")
                if (failingWorker) {
                    File(root, "functions").mkdir()
                    File(root, "functions/compinit").writeText("/bin/sleep 3; return 1\n")
                    source = "fpath=($guest/functions ${'$'}fpath)\n$source"
                }
                File(root, ".zshrc").writeText(source)
                manager.prepareTypeface()
            }
            val command = runtime.command(id, settings, null).let { original ->
                original.copy(arguments = original.arguments.map {
                    when {
                        it.startsWith("ZDOTDIR=") -> "ZDOTDIR=$guest"
                        it == "/etc/zakocoder/bash/bashrc" -> "$guest/.zshrc"
                        else -> it
                    }
                })
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val item = manager.create(id, "Tab fixture", command)
                    view = manager.view(activity, item, 9)
                    activity.setContentView(android.widget.FrameLayout(activity).apply { addView(view) })
                    view.requestFocus()
                }
                suspend fun input(text: String) = withContext(Dispatchers.Main) {
                    assertTrue("Helper keys must retain IME focus", view.hasFocus())
                    assertTrue(view.onCreateInputConnection(EditorInfo()).commitText(text, 1))
                }
                suspend fun awaitFile(name: String, timeout: Long = 2000, contents: String? = null) = withTimeout(timeout) {
                    while (!withContext(Dispatchers.IO) {
                        File(root, name).let { it.isFile && (contents == null || it.readText() == contents) }
                    }) delay(20)
                }
                input("printf ready > prompt-ready\n")
                awaitFile("prompt-ready", 5000)
                // Complete a real filename through the same route used by the visible Tab button.
                // The old first-Tab widget blocks for tens of seconds with this cold cache.
                repeat(10) { round ->
                    val started = SystemClock.elapsedRealtime()
                    input("cat completion-tar")
                    withContext(Dispatchers.Main) { manager.key(view, KeyEvent.KEYCODE_TAB) }
                    input(" > result-$round.txt\n")
                    awaitFile("result-$round.txt", contents = "TAB_INPUT_中文_OK\n")
                    assertEquals("TAB_INPUT_中文_OK\n", withContext(Dispatchers.IO) { File(root, "result-$round.txt").readText() })
                    println("TERMINAL_TAB_ROUND shell=$shell failed_worker=$failingWorker round=$round elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                }
                if (failingWorker) {
                    delay(3500)
                    input("printf still-usable > worker-finished\n")
                    awaitFile("worker-finished")
                } else if (shell == TerminalShell.Zsh) {
                    withTimeout(120_000) {
                        while (!withContext(Dispatchers.IO) { File(root, "full-completion-ready").isFile }) {
                            input("if (( ${'$'}+functions[compdef] )); then : > full-completion-ready; fi\n")
                            delay(500)
                        }
                    }
                }
                // Both successful attachment and worker failure must leave subsequent input usable.
                input("cat completion-tar")
                withContext(Dispatchers.Main) { manager.key(view, KeyEvent.KEYCODE_TAB) }
                input(" > final-result.txt\n")
                awaitFile("final-result.txt", contents = "TAB_INPUT_中文_OK\n")
                assertEquals("TAB_INPUT_中文_OK\n", withContext(Dispatchers.IO) { File(root, "final-result.txt").readText() })
                input("printf '%s' '中文输入' > ime-proof; alias ls > alias-definition; ls > redirected-list.txt\n")
                awaitFile("redirected-list.txt")
                withContext(Dispatchers.IO) {
                    assertEquals("中文输入", File(root, "ime-proof").readText())
                    assertTrue(File(root, "alias-definition").readText().contains("ls --color=auto"))
                    assertFalse("Redirected output must remain plain", File(root, "redirected-list.txt").readText().contains('\u001b'))
                }
                input("ls -d COLOR_DIRECTOR*\n")
                withTimeout(2000) {
                    while (!withContext(Dispatchers.Main) {
                        val emulator = view.currentSession.emulator
                        val row = (-emulator.screen.activeTranscriptRows until emulator.mRows).firstOrNull {
                            emulator.screen.getSelectedText(0, it, emulator.mColumns, it).trim() == "COLOR_DIRECTORY_MARKER"
                        }
                        row != null && com.termux.terminal.TextStyle.decodeForeColor(emulator.screen.getStyleAt(row, 0)) !=
                            com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND
                    }) delay(20)
                }
                withContext(Dispatchers.Main) {
                    assertTrue(view.currentSession.isRunning)
                    assertTrue(view.hasFocus())
                }
                val batch = runtime.execute("if alias ls >/dev/null 2>&1; then exit 1; fi", id, settings, timeoutMillis = 5000)
                assertEquals("Noninteractive commands must not load terminal aliases", 0, batch.exitCode)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (::view.isInitialized) manager.detach(view)
                manager.closeAll()
            }
            withContext(NonCancellable + Dispatchers.IO) {
                PrivateFiles.deleteTree(root)
                PrivateFiles.deleteTree(fixture)
            }
        }
    }
}
