package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.runtime.OwnedProcesses
import dev.zakocoder.app.runtime.RuntimeCommand
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opt-in, offline probe in the actual app process; only its UUID workspace is removed. */
class ProotHardLinkDeviceTest {
    private suspend fun runOwned(context: android.content.Context, command: RuntimeCommand, timeoutMillis: Long = 45000): CommandResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf("/system/bin/sh", "-c", "printf '%s\\n' \"${'$'}${'$'}\"; exec \"${'$'}@\"", "link-probe", command.executable) + command.arguments)
            .directory(context.filesDir).redirectErrorStream(true)
        builder.environment().clear(); builder.environment().putAll(command.environment)
        val process = builder.start()
        val reader = process.inputStream.bufferedReader()
        val ownedPid = java.util.concurrent.atomic.AtomicInteger(0)
        var worker: Thread? = null
        fun stopOwned() {
            val pid = ownedPid.get()
            if (process.isAlive && pid > 0) OwnedProcesses.terminate(pid)
            process.destroyForcibly()
        }
        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { stopOwned() }
                    worker = thread(name = "ZakoLinkProbe") {
                        try {
                            val pid = reader.readLine()?.toIntOrNull() ?: error("Missing owned probe PID")
                            ownedPid.set(pid)
                            if (!continuation.isActive) { stopOwned(); return@thread }
                            val text = StringBuilder(); val buffer = CharArray(4096)
                            reader.use {
                                while (true) { val count = it.read(buffer); if (count < 0) break; text.append(buffer, 0, count); if (text.length > 24000) text.delete(0, text.length - 24000) }
                            }
                            val result = CommandResult(process.waitFor(), text.toString())
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                stopOwned()
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                worker?.join(3000)
                reader.close()
                check(worker?.isAlive != true) { "Owned probe reader did not stop" }
            }
        }
    }

    @Test(timeout = 180000) fun canonicalDepotsKeepLinkedExecutablesAndDirectoryCleanupUsable(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prootLinkProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (w.state.value.loading) delay(50) }
            assertTrue(w.state.value.installed)
            assertFalse(w.state.value.busy || w.ai.state.value.active || w.projectTasks.state.value.busy)
            val results = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for ((mode, spelling) in listOf("project" to "absolute", "project" to "canonical", "rootfs" to "absolute", "rootfs" to "canonical")) {
                val project = withContext(Dispatchers.IO) { w.projects.create("canonical-depot-probe") }
                val root = w.projects.directory(project.id)
                val depot = if (mode == "project") File(root, "depot") else File(w.runtime.root, ".zakocoder-link-probe-${UUID.randomUUID()}")
                try {
                    withContext(Dispatchers.IO) {
                        check(depot.mkdir()); File(root, "link-probe.cjs").writeText(PROBE); File(root, "exec-probe.cjs").writeText(EXEC_PROBE)
                    }
                    val command = w.runtime.command(project.id, w.state.value.settings, "node exec-probe.cjs && node link-probe.cjs")
                    val configured = if (spelling == "canonical") depot.canonicalPath else depot.absolutePath
                    val result = runOwned(context, command.copy(environment = command.environment + ("PROOT_L2S_DIR" to configured)))
                    val summary = "DEPOT=$mode spelling=$spelling aliasesDiffer=${depot.canonicalPath != depot.absolutePath} exit=${result.exitCode} exec=${result.output.contains("LINKED_EXECUTABLE_OK")} cleanup=${result.output.contains("HARDLINK_CLEANUP_OK")}"
                    android.util.Log.i("ZakoProotProbe", summary)
                    val report = "$summary\n${result.output}"
                    println(report); results += report
                    if (spelling == "canonical" && (result.exitCode != 0 || !result.output.contains("LINKED_EXECUTABLE_OK") || !result.output.contains("HARDLINK_CLEANUP_OK"))) failed += mode
                } finally { withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root); if (mode == "rootfs") PrivateFiles.deleteTree(depot) } }
            }
            assertTrue(results.joinToString("\n"), failed.isEmpty())
        }
    }

    @Test(timeout = 240000) fun canonicalRootDepotCanInstallBuildAndRepeatOfflineNpmCiWithoutChangingSource(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prootLinkProbe") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkDependencyProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (w.state.value.loading) delay(50) }
            assertTrue(w.state.value.installed)
            assertFalse(w.state.value.busy || w.ai.state.value.active || w.projectTasks.state.value.busy)
            val project = withContext(Dispatchers.IO) { w.projects.create("depot-npm-proof") }
            val root = w.projects.directory(project.id)
            try {
                val source = mapOf(
                    "package.json" to """{"private":true,"scripts":{"build":"vite build"},"devDependencies":{"vite":"5.4.21"}}""",
                    "index.html" to "<!doctype html><title>owned depot probe</title><script type=module src=/main.js></script>",
                    "main.js" to "document.body.textContent = 'real Vite depot build';",
                )
                withContext(Dispatchers.IO) { source.forEach { (name, text) -> File(root, name).writeText(text) } }
                val script = """
                    set -eu
                    export NPM_CONFIG_USERCONFIG="${'$'}PWD/user.npmrc" NPM_CONFIG_GLOBALCONFIG="${'$'}PWD/global.npmrc" NPM_CONFIG_UPDATE_NOTIFIER=false
                    test "${'$'}(npm --version)" = '10.9.4'
                    npm install --cache ./.test-cache --registry=https://registry.npmjs.org --no-audit --no-fund --fetch-retries=0 --fetch-timeout=30000
                    npm run build
                    test -s dist/index.html
                    sha256sum package-lock.json > lock.expected
                    for round in 1 2 3; do
                      npm ci --cache ./.test-cache --offline --no-audit --no-fund
                      sha256sum -c lock.expected
                      npm run build
                      test -s dist/index.html
                      printf 'CANDIDATE_CI_ROUND_OK:%s\n' "${'$'}round"
                    done
                """.trimIndent()
                val result = w.runtime.execute(script, project.id, w.state.value.settings, timeoutMillis = 180000)
                assertEquals(result.output, 0, result.exitCode)
                for (round in 1..3) assertTrue(result.output, result.output.contains("CANDIDATE_CI_ROUND_OK:$round"))
                withContext(Dispatchers.IO) { source.forEach { (name, text) -> assertEquals(name, text, File(root, name).readText()) } }
                android.util.Log.i("ZakoProotProbe", "NPM_INSTALL_BUILD_CI_3_BUILD_OK sourceUnchanged=true")
            } finally { withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) } }
        }
    }

    @Test(timeout = 120000) fun nodeHardLinkCleanupPreservesOtherLinksAndRemovesOwnedDirectories(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prootLinkProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (w.state.value.loading) delay(50) }
            assertTrue(w.state.value.installed)
            assertFalse(w.state.value.busy || w.ai.state.value.active || w.projectTasks.state.value.busy)
            val project = withContext(Dispatchers.IO) { w.projects.create("offline-link-probe") }
            val root = w.projects.directory(project.id)
            try {
                withContext(Dispatchers.IO) { File(root, "link-probe.cjs").writeText(PROBE) }
                val result = w.runtime.execute("node link-probe.cjs", project.id, w.state.value.settings, timeoutMillis = 90000)
                val physical = withContext(Dispatchers.IO) {
                    Files.walk(root.toPath()).use { paths -> paths.limit(100).map { path ->
                        val relative = root.toPath().relativize(path).toString()
                        relative + if (Files.isSymbolicLink(path)) " -> " + Files.readSymbolicLink(path).toString().take(300) else ""
                    }.toArray().joinToString("\n").replace(root.absolutePath, "<owned-project>") }
                }
                assertEquals(result.output + "\nPHYSICAL_OWNED_TREE\n" + physical, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("HARDLINK_CLEANUP_OK"))
            } finally { withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) } }
        }
    }

    @Test(timeout = 120000) fun linkedExecutablesSurviveSeparateProductCommands(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prootLinkProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (w.state.value.loading) delay(50) }
            assertTrue(w.state.value.installed)
            assertFalse(w.state.value.busy || w.ai.state.value.active || w.projectTasks.state.value.busy)
            val project = withContext(Dispatchers.IO) { w.projects.create("persistent-link-proof") }
            val root = w.projects.directory(project.id)
            try {
                withContext(Dispatchers.IO) { File(root, "exec-probe.cjs").writeText(EXEC_PROBE) }
                val first = w.runtime.execute("node exec-probe.cjs", project.id, w.state.value.settings, timeoutMillis = 45000)
                assertEquals(first.output, 0, first.exitCode)
                val second = w.runtime.execute("./elf-linked && test \"${'$'}(./script-linked)\" = LINKED_SCRIPT_OK && rm elf-linked script-linked && printf PERSISTENT_LINK_OK", project.id, w.state.value.settings, timeoutMillis = 45000)
                assertEquals(second.output, 0, second.exitCode)
                assertTrue(second.output, second.output.contains("PERSISTENT_LINK_OK"))
            } finally { withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) } }
        }
    }

    @Test(timeout = 120000) fun ptyAndCommandCanSharePersistentDepot(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prootLinkProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000) { while (w.state.value.loading) delay(50) }
            assertTrue(w.state.value.installed)
            assertFalse(w.state.value.busy || w.ai.state.value.active || w.projectTasks.state.value.busy)
            val project = withContext(Dispatchers.IO) { w.projects.create("shared-link-proof") }
            val root = w.projects.directory(project.id)
            var terminalId: String? = null
            try {
                withContext(Dispatchers.IO) {
                    for (name in listOf("pty-owned", "command-owned")) {
                        val directory = File(root, name).apply { mkdir() }
                        File(directory, "link-probe.cjs").writeText(PROBE)
                    }
                }
                val command = w.runtime.command(project.id, w.state.value.settings,
                    "touch pty-ready; while ! test -f command-ready; do sleep 0.05; done; cd pty-owned && node link-probe.cjs")
                val item = withContext(Dispatchers.Main) {
                    w.terminal.create(project.id, project.name, command).also { terminalId = it.id }
                }
                val result = w.runtime.execute(
                    "touch command-ready; while ! test -f pty-ready; do sleep 0.05; done; cd command-owned && node link-probe.cjs",
                    project.id, w.state.value.settings, timeoutMillis = 45000)
                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("HARDLINK_CLEANUP_OK"))
                withTimeout(20000) { while (withContext(Dispatchers.Main) { item.session.isRunning }) delay(50) }
                withContext(Dispatchers.Main) {
                    assertEquals(0, item.session.exitStatus)
                    assertTrue(item.session.emulator.screen.transcriptText.contains("HARDLINK_CLEANUP_OK"))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { terminalId?.let { w.terminal.close(it) } }
                withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) }
            }
        }
    }

    companion object {
        private val EXEC_PROBE = """
            'use strict';
            const fs = require('node:fs');
            const exec = require('node:child_process').execFileSync;
            const assert = require('node:assert/strict');
            fs.copyFileSync('/usr/bin/true', 'elf-original');
            fs.chmodSync('elf-original', 0o755);
            fs.linkSync('elf-original', 'elf-linked');
            exec('./elf-linked');
            fs.unlinkSync('elf-original');
            exec('./elf-linked');
            fs.writeFileSync('script-original', '#!/bin/sh\nprintf LINKED_SCRIPT_OK', {mode: 0o755});
            fs.linkSync('script-original', 'script-linked');
            assert.equal(exec('./script-linked', {encoding: 'utf8'}), 'LINKED_SCRIPT_OK');
            fs.unlinkSync('script-original');
            assert.equal(exec('./script-linked', {encoding: 'utf8'}), 'LINKED_SCRIPT_OK');
            console.log('LINKED_EXECUTABLE_OK');
        """.trimIndent()

        private val PROBE = """
            'use strict';
            const fs = require('node:fs');
            const path = require('node:path');
            const assert = require('node:assert/strict');
            async function main() {
              console.log('NODE', process.version);
              for (let round = 0; round < 32; round++) {
                for (const mode of ['source-first', 'target-first', 'parallel-tree']) {
                  const base = 'round-' + round + '-' + mode;
                  const source = path.join(base, '@esbuild/linux-arm64/bin/esbuild');
                  const target = path.join(base, 'esbuild/bin/esbuild');
                  fs.mkdirSync(path.dirname(source), {recursive: true});
                  fs.mkdirSync(path.dirname(target), {recursive: true});
                  fs.writeFileSync(source, 'owned-link-content-' + round);
                  fs.linkSync(source, target);
                  assert.equal(fs.readFileSync(target, 'utf8'), fs.readFileSync(source, 'utf8'));
                  console.log('CASE', round, mode);
                  if (mode === 'source-first') {
                    await fs.promises.rm(path.join(base, '@esbuild'), {recursive: true});
                    assert.equal(fs.readFileSync(target, 'utf8'), 'owned-link-content-' + round);
                    await fs.promises.rm(base, {recursive: true});
                  } else if (mode === 'target-first') {
                    await fs.promises.rm(path.join(base, 'esbuild'), {recursive: true});
                    assert.equal(fs.readFileSync(source, 'utf8'), 'owned-link-content-' + round);
                    await fs.promises.rm(base, {recursive: true});
                  } else {
                    await fs.promises.rm(base, {recursive: true});
                  }
                  assert.equal(fs.existsSync(base), false);
                }
              }
              console.log('HARDLINK_CLEANUP_OK');
            }
            main().catch(error => {
              console.error(JSON.stringify({code: error.code, syscall: error.syscall, path: error.path, message: error.message}));
              process.exitCode = 1;
            });
        """.trimIndent()
    }
}
