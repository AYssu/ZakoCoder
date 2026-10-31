package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Verifies the actual installed APK's binaries and guest filesystem/tool contracts, offline. */
class NativeRuntimeArtifactDeviceTest {
    @Test fun installedNativeFilesMatchSelfBuiltManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = JSONObject(context.assets.open("runtime-artifacts.json").bufferedReader().use { it.readText() })
        assertTrue(manifest.getJSONObject("nativeSource").getBoolean("selfBuilt"))
        // PackageManager's installed ISA directory identifies the actual process ABI.
        // SUPPORTED_ABIS also lists unselected ABIs and is not an artifact selector.
        val selected = when (File(context.applicationInfo.nativeLibraryDir).name) {
            "arm64" -> manifest
            "x86_64" -> manifest.getJSONObject("architectures").getJSONObject("x86_64")
            else -> error("No native artifact contract for the installed app ABI")
        }
        val artifacts = selected.getJSONArray("nativeArtifacts")
        repeat(artifacts.length()) { index ->
            val artifact = artifacts.getJSONObject(index)
            val file = File(context.applicationInfo.nativeLibraryDir, artifact.getString("name"))
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(file.name, artifact.getString("sha256"), digest)
            assertTrue(file.canExecute())
        }
    }

    @Test(timeout = 120000) fun persistentLinksArchivePythonNodeAndOfflineNpm() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LinuxRuntime(context)
        assertTrue("Install Ubuntu, Python and Node using the app first", runtime.isInstalled())
        val id = UUID.randomUUID().toString()
        val directory = File(runtime.workspaces, id)
        check(directory.mkdirs())
        try {
            val first = runtime.execute("""
                set -eu
                test "${'$'}(id -u)" = 0
                test "${'$'}(pwd)" = /workspace/$id
                mkdir data
                printf 'persistent-中文' > 'data/file with space'
                ln 'data/file with space' 'data/hard link'
                ln -s 'file with space' data/symbolic
                tar -cf archive.tar data
                mkdir extracted
                tar -xf archive.tar -C extracted
                cmp 'extracted/data/hard link' 'data/file with space'
                test "${'$'}(cat extracted/data/symbolic)" = persistent-中文
                python3 - <<'PY'
                import os, sqlite3, subprocess, socket, threading
                assert os.getcwd() == '/workspace/$id'
                assert os.getuid() == os.geteuid() == 0
                os.setresuid(0, 0, 0)
                db = sqlite3.connect('probe.db')
                db.execute('create table proof(value text)')
                db.execute('insert into proof values (?)', ('中文',))
                db.commit()
                assert db.execute('select value from proof').fetchone()[0] == '中文'
                left, right = socket.socketpair()
                t = threading.Thread(target=lambda: right.sendall(b'42'))
                t.start()
                assert left.recv(2) == b'42'
                t.join(); left.close(); right.close(); db.close()
                assert subprocess.check_output(['/bin/bash', '-c', 'printf child']) == b'child'
                PY
                node -e "if (require('fs').readFileSync('data/hard link','utf8') !== 'persistent-中文') process.exit(1)"
                mkdir package
                printf '%s' '{"name":"zakocoder-native-probe","version":"1.0.0"}' > package/package.json
                printf 'module.exports = 42;\n' > package/index.js
                (cd package; npm pack --ignore-scripts --offline --cache ../npm-cache --loglevel error)
                npm cache add ./package/zakocoder-native-probe-1.0.0.tgz --offline --cache ./npm-cache --loglevel error
                npm cache verify --offline --cache ./npm-cache --loglevel error
                printf 'NATIVE_TOOLCHAIN_CONTRACT_OK\n'
            """.trimIndent(), id, AppSettings(), timeoutMillis = 90000)
            assertEquals(first.output, 0, first.exitCode)
            assertTrue(first.output, first.output.contains("NATIVE_TOOLCHAIN_CONTRACT_OK"))
            // A new PRoot process must resolve the previous process's persistent link depot.
            val second = runtime.execute("""
                set -eu
                test "${'$'}(cat 'data/hard link')" = persistent-中文
                printf second-process > 'data/file with space'
                test "${'$'}(cat 'data/hard link')" = second-process
                python3 -c "import sqlite3; assert sqlite3.connect('probe.db').execute('select value from proof').fetchone()[0] == '中文'"
                printf 'PERSISTENT_LINKS_OK\n'
            """.trimIndent(), id, AppSettings())
            assertEquals(second.output, 0, second.exitCode)
            assertTrue(second.output, second.output.contains("PERSISTENT_LINKS_OK"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
