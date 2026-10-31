package dev.zakocoder.app.runtime

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.StatFs
import android.system.Os
import dev.zakocoder.app.R
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.NetworkProxy
import dev.zakocoder.app.settings.TerminalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CommandResult(val exitCode: Int, val output: String, val runId: String? = null)
data class RuntimeCommand(val executable: String, val arguments: List<String>, val environment: Map<String, String>)

class LinuxRuntime(private val context: Context, private val proxyCredentials: () -> NetworkProxy.Credentials? = { null }) {
    /** Installed by Workbench; invoked on IO before every project process. */
    var projectDirectory: ((String) -> File)? = null
    val home = File(context.filesDir, "linux-runtime")
    val root = File(home, "rootfs")
    val workspaces = File(context.filesDir, "workspaces")
    private val temporary = File(home, "tmp")
    private val manifest by lazy { JSONObject(context.assets.open("runtime-artifacts.json").bufferedReader().use { it.readText() }) }
    private val architecture get() = RuntimeArchitecture.forNativeLibraryDirectory(nativeDirectory, Process.is64Bit())
    private fun selectedArtifacts(): JSONObject = when (architecture) {
        RuntimeArchitecture.Arm64 -> manifest
        RuntimeArchitecture.X86_64 -> manifest.getJSONObject("architectures").getJSONObject("x86_64")
        null -> error(context.getString(R.string.runtime_abi_unsupported))
    }
    private val image get() = selectedArtifacts().getJSONObject("rootfs")

    /** Managed Android SDK/NDK archives currently contain ARM64 executables only. */
    fun requireAndroidToolchainSupport() {
        require(architecture == RuntimeArchitecture.Arm64) { context.getString(R.string.android_toolchain_abi_unsupported) }
    }
    private val nativeDirectory get() = context.applicationInfo.nativeLibraryDir
    val imageVersion: String get() = image.getString("version")

    fun isInstalled(): Boolean = File(root, ".zakocoder-ready").let {
        it.isFile && it.readText() == image.getString("sha256") && File(root, "usr/bin/bash").isFile
    }

    fun freeBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    /**
     * Settings validation runs before saving, but a value persisted by an older build can still be
     * malformed. Translate that into the same localized error instead of surfacing an English parse
     * exception, and never fall back to a silent direct connection.
     */
    private fun <T> proxySetting(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(context.getString(R.string.invalid_proxy))
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun install(settings: AppSettings, offline: Uri? = null, status: (String) -> Unit) = withContext(Dispatchers.IO) {
        require(architecture != null) { context.getString(R.string.runtime_abi_unsupported) }
        check(!root.exists()) { context.getString(R.string.runtime_already_present) }
        require(freeBytes() > 600L * 1024 * 1024) { context.getString(R.string.storage_insufficient) }
        val nativeArtifacts = selectedArtifacts().getJSONArray("nativeArtifacts")
        for (index in 0 until nativeArtifacts.length()) {
            val artifact = nativeArtifacts.getJSONObject(index)
            check(checksum(File(nativeDirectory, artifact.getString("name"))) == artifact.getString("sha256")) {
                context.getString(R.string.checksum_failed)
            }
        }
        temporary.mkdirs()
        val coroutine = currentCoroutineContext()
        val cache = File(context.cacheDir, "runtime-download").apply { mkdirs() }
        val archive = File(cache, "rootfs.tar.xz")
        if (archive.isFile && checksum(archive) != image.getString("sha256")) archive.delete()
        if (offline != null) {
            val imported = File(cache, "offline.partial")
            try {
                context.contentResolver.openInputStream(offline)!!.use { input -> imported.outputStream().use { output ->
                    val buffer = ByteArray(65536); var bytes = 0L
                    while (true) {
                        coroutine.ensureActive(); val count = input.read(buffer); if (count < 0) break
                        bytes += count; require(bytes <= image.getLong("bytes")) { context.getString(R.string.checksum_failed) }
                        output.write(buffer, 0, count)
                    }
                } }
                check(checksum(imported) == image.getString("sha256")) { context.getString(R.string.checksum_failed) }
                check(imported.renameTo(archive))
            } finally { imported.delete() }
        }
        if (!archive.exists()) {
            val partial = File(cache, "rootfs.partial")
            // SOCKS5 uses Proxy.Type.SOCKS; plain HTTP keeps the previous HttpURLConnection proxy path.
            val rawProxy = proxySetting { NetworkProxy.withCredentials(settings.proxy, proxyCredentials()) }
            val proxy = proxySetting { NetworkProxy.javaProxy(rawProxy) } ?: java.net.Proxy.NO_PROXY
            // The JDK carries SOCKS5 RFC1929 credentials through the global authenticator only.
            // Bind them to this exact endpoint so a settings switch can never send them elsewhere.
            NetworkProxy.configureSocksAuthentication(rawProxy)
            val connection = URL(image.getString("url")).openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            try {
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                require(connection.url.protocol == "https")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var downloaded = 0L
                        var previousPercent = -1
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            require(downloaded <= image.getLong("bytes")) { context.getString(R.string.checksum_failed) }
                            output.write(buffer, 0, count)
                            val percent = (downloaded * 100 / image.getLong("bytes")).toInt()
                            if (percent != previousPercent) {
                                status(context.getString(R.string.runtime_downloading, percent))
                                previousPercent = percent
                            }
                        }
                        check(downloaded == image.getLong("bytes")) { context.getString(R.string.checksum_failed) }
                    }
                }
                status(context.getString(R.string.runtime_verifying))
                check(checksum(partial) == image.getString("sha256")) { context.getString(R.string.checksum_failed) }
                check(partial.renameTo(archive))
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }
        val staging = File(home, "installing")
        PrivateFiles.deleteTree(staging)
        staging.mkdirs()
        try {
            status(context.getString(R.string.runtime_extracting, 0))
            ArchiveExtractor.extract(archive, staging, image.getString("prefix"), { coroutine.ensureActive() }) {
                status(context.getString(R.string.runtime_extracting, it))
            }
            configure(settings, staging)
            status(context.getString(R.string.runtime_self_test))
            val result = execute("set -e; test \"${'$'}(/bin/pwd)\" = /root; test \"${'$'}(id -u)\" = 0; printf 'ZAKOCODER_RUNTIME_OK\\n'; /bin/bash --version | head -n 1", null, settings, staging)
            check(result.exitCode == 0 && result.output.contains("ZAKOCODER_RUNTIME_OK")) { result.output.takeLast(3000) }
            File(staging, ".zakocoder-ready").writeText(image.getString("sha256"))
            check(staging.renameTo(root))
        } finally {
            PrivateFiles.deleteTree(staging)
        }
    }

    fun configure(settings: AppSettings, target: File = root) {
        PrivateFiles.writeConfined(target, "etc/resolv.conf", settings.dns.split(',').joinToString("\n", postfix = "\n") {
            "nameserver ${it.trim().split('.').joinToString(".") { part -> part.toInt().toString() }}"
        })
        PrivateFiles.writeConfined(target, "etc/apt/sources.list.d/ubuntu.sources", """
            Types: deb
            URIs: ${architecture?.aptBaseUrl(settings.aptSource) ?: error(context.getString(R.string.runtime_abi_unsupported))}
            Suites: noble noble-updates noble-security
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
        """.trimIndent() + "\n")
        PrivateFiles.writeConfined(target, "etc/apt/sources.list", "")
        // APT's AI_ADDRCONFIG rejects loopback HTTP proxies when Android is offline.
        // SOCKS5 has no apt/HTTP-proxy equivalent here, so its workaround file stays empty.
        PrivateFiles.writeConfined(target, "etc/apt/apt.conf.d/99zakocoder-network",
            if (proxySetting { NetworkProxy.aptAddrConfigWorkaround(settings.proxy) }) "Acquire::Connect::AddrConfig \"false\";\n" else "")
        File(target, "root").mkdirs()
        File(target, "tmp").mkdirs()
        File(target, "workspace").mkdirs()
    }

    /** Called on IO before creating a PTY; never replaces a user's own startup files. */
    fun prepareTerminal(settings: AppSettings) {
        check(File(root, settings.terminalShell.executable.removePrefix("/")).isFile) {
            context.getString(R.string.shell_not_installed, settings.terminalShell.name)
        }
        if (settings.terminalShell == TerminalShell.Zsh) {
            // Ubuntu's global zshrc otherwise runs compinit before our managed configuration.
            PrivateFiles.writeConfined(root, "etc/zakocoder/zsh/.zshenv", "skip_global_compinit=1\n")
            PrivateFiles.writeConfined(root, "etc/zakocoder/zsh/.zshrc", """
                autoload -Uz compinit
                bindkey -e
                if [[ -s /root/.zshrc ]]; then
                    # Existing user config may call compdef during startup.
                    compinit -i -d /root/.cache/zakocoder/zcompdump
                else
                    # PRoot makes completion-directory checks expensive. Pay that cost on first Tab,
                    # retaining compinit's ownership/security checks and normal completion behavior.
                    _zakocoder_complete() {
                        bindkey '^I' expand-or-complete
                        compinit -i -d /root/.cache/zakocoder/zcompdump
                        zle expand-or-complete
                    }
                    zle -N _zakocoder_complete
                    bindkey '^I' _zakocoder_complete
                fi
                HISTFILE=/root/.cache/zakocoder/zsh_history
                HISTSIZE=5000
                SAVEHIST=5000
                setopt append_history
                PROMPT='%F{#9AA7B8}%1~%f %F{#8AB4F8}›%f '
                PROMPT_EOL_MARK='%F{#6B7684}↵%f'
                if [[ -r /root/.zshrc ]]; then source /root/.zshrc; fi
                # Named directories reject whitespace. Keep the label as prompt data instead.
                # A user's custom prompt takes precedence over our managed default.
                if [[ ${'$'}PROMPT == '%F{#9AA7B8}%1~%f %F{#8AB4F8}›%f ' ]]; then
                    _ZAKOCODER_LAST_PROMPT=${'$'}PROMPT
                    _zakocoder_prompt() {
                        [[ ${'$'}PROMPT == "${'$'}_ZAKOCODER_LAST_PROMPT" ]] || return 0
                        local location='%1~'
                        if [[ -n ${'$'}ZAKOCODER_PROJECT_LABEL && ${'$'}PWD == "${'$'}ZAKOCODER_PROJECT_PATH" ]]; then
                            location=${'$'}{ZAKOCODER_PROJECT_LABEL//\%/%%}
                        fi
                        PROMPT='%F{#9AA7B8}'"${'$'}location"'%f %F{#8AB4F8}›%f '
                        _ZAKOCODER_LAST_PROMPT=${'$'}PROMPT
                    }
                    autoload -Uz add-zsh-hook
                    add-zsh-hook precmd _zakocoder_prompt
                fi
            """.trimIndent() + "\n")
            PrivateFiles.writeConfined(root, "root/.cache/zakocoder/.managed", "")
        }
    }

    suspend fun command(projectId: String?, settings: AppSettings, script: String?, target: File = root, projectName: String? = null): RuntimeCommand = withContext(Dispatchers.IO) {
        // These absolute host paths survive deleting either guest hard-link name.
        // Android /data/user/0 and /data/data aliases must resolve to the same spelling.
        val canonicalRoot = target.canonicalFile
        val links = File(canonicalRoot, ".zakocoder-links")
        require(canonicalRoot.isDirectory && !Files.isSymbolicLink(links.toPath())) { context.getString(R.string.directory_unreadable) }
        Files.createDirectories(links.toPath())
        require(!Files.isSymbolicLink(links.toPath()) && links.canonicalFile.parentFile == canonicalRoot) { context.getString(R.string.directory_unreadable) }
        val cwd = if (projectId == null) "/root" else {
            require(java.util.UUID.fromString(projectId).toString() == projectId)
            "/workspace/$projectId"
        }
        val androidMarker = File(target, AndroidToolchain.MARKER.removePrefix("/"))
        val androidReady = architecture == RuntimeArchitecture.Arm64 && androidMarker.isFile && androidMarker.length() == AndroidToolchain.VERSION.length + 1L &&
            androidMarker.readText().trim() == AndroidToolchain.VERSION
        val ndkMarker = File(target, AndroidNdkToolchain.MARKER.removePrefix("/"))
        val ndkReady = androidReady && ndkMarker.isFile && ndkMarker.length() == AndroidNdkToolchain.VERSION.length + 1L &&
            ndkMarker.readText().trim() == AndroidNdkToolchain.VERSION
        val path = listOf(settings.additionalPath, if (androidReady) AndroidToolchain.PATH else "",
            if (ndkReady) AndroidNdkToolchain.PATH else "", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            .filter(String::isNotEmpty).joinToString(":")
        // HTTP exports http_proxy/https_proxy; SOCKS5 exports only ALL_PROXY (never a fake http_proxy).
        // The guest ALL_PROXY URL carries URL-encoded SOCKS5 credentials: they are visible to every
        // process started inside the guest environment (documented trade-off, see evidence).
        val credentials = proxyCredentials()
        val network = proxySetting { NetworkProxy.guestEnvironment(NetworkProxy.withCredentials(settings.proxy, credentials)) }
        val executable = File(nativeDirectory, "libzakocoder_proot.so").absolutePath
        val args = mutableListOf(
            "--kill-on-exit", "--link2symlink", "-0", "-r", target.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "${workspaces.absolutePath}:/workspace",
            "-b", "${temporary.absolutePath}:/tmp", "-w", cwd,
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=$path", "TMPDIR=/tmp", "DEBIAN_FRONTEND=noninteractive",
        )
        if (projectId != null) {
            val source = projectDirectory?.invoke(projectId) ?: File(workspaces, projectId).also {
                require(it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                    it.canonicalFile.toPath().startsWith(workspaces.canonicalFile.toPath())) { context.getString(R.string.directory_unreadable) }
            }
            // Insert the explicit project bind before argv of /usr/bin/env, never into shell text.
            val envIndex = args.indexOf("/usr/bin/env")
            args.addAll(envIndex, listOf("-b", "${source.absolutePath}:/workspace/$projectId"))
        }
        if (projectId != null && script == null) {
            // A friendly prompt is display-only. Do not interpolate project names into shell source.
            val label = projectName.orEmpty().map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }
                .joinToString("").take(24).ifBlank { "workspace" }
            args += listOf("ZAKOCODER_PROJECT_PATH=$cwd", "ZAKOCODER_PROJECT_LABEL=$label",
                "PS1=${'$'}{PWD/#${'$'}ZAKOCODER_PROJECT_PATH/${'$'}ZAKOCODER_PROJECT_LABEL} \\$ ")
        } else args += "PS1=\\w \\$ "
        args += network
        if (androidReady) args += AndroidToolchain.environment(settings.proxy)
        if (ndkReady) args += AndroidNdkToolchain.environment()
        args += "SHELL=${if (script == null) settings.terminalShell.executable else "/bin/bash"}"
        if (script == null && settings.terminalShell == TerminalShell.Zsh) {
            args += listOf("ZDOTDIR=/etc/zakocoder/zsh", "/usr/bin/zsh", "-i")
        } else {
            // Installers and probes always use Bash, irrespective of the interactive preference.
            args += listOf("/bin/bash", "--noprofile", "--norc")
            if (script == null) args += "-i" else args += listOf("-c", script)
        }
        RuntimeCommand(executable, args, mapOf(
            "PROOT_L2S_DIR" to links.canonicalPath,
            "PROOT_LOADER" to File(nativeDirectory, "libzakocoder_loader.so").absolutePath,
            "PROOT_TMP_DIR" to temporary.absolutePath,
            "TMPDIR" to temporary.absolutePath, "PATH" to "/system/bin", "HOME" to context.filesDir.absolutePath,
        ))
    }

    suspend fun execute(
        script: String, projectId: String?, settings: AppSettings, target: File = root,
        timeoutMillis: Long = 30 * 60 * 1000L, output: (String) -> Unit = {},
    ): CommandResult = withTimeout(timeoutMillis) {
        withContext(Dispatchers.IO) {
            if (projectId != null) {
                require(java.util.UUID.fromString(projectId).toString() == projectId)
                projectDirectory?.invoke(projectId) ?: File(workspaces, projectId).also { directory ->
                    require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
                        directory.canonicalFile.toPath().startsWith(workspaces.canonicalFile.toPath())) { context.getString(R.string.directory_unreadable) }
                }
            }
            val command = command(projectId, settings, script, target)
            // Fixed host wrapper reports its own PID, then replaces itself with PRoot.
            // User/project text remains an argv element of the guest bash, never host shell source.
            val builder = ProcessBuilder(listOf("/system/bin/sh", "-c", "printf '%s\\n' \"${'$'}${'$'}\"; exec \"${'$'}@\"", "zakocoder", command.executable) + command.arguments)
                .directory(context.filesDir).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(command.environment)
            val process = builder.start()
            val reader = process.inputStream.bufferedReader()
            val pid = reader.readLine()?.toIntOrNull() ?: run { process.destroyForcibly(); error("Could not obtain owned process PID") }
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    OwnedProcesses.terminate(pid)
                    process.destroyForcibly()
                    process.waitFor(3, TimeUnit.SECONDS)
                }
                thread(name = "ZakoCommand-$pid") {
                    try {
                        val transcript = StringBuilder()
                        reader.use { reader ->
                            val buffer = CharArray(4096)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                val chunk = String(buffer, 0, count)
                                transcript.append(chunk)
                                if (transcript.length > 100000) transcript.delete(0, transcript.length - 100000)
                                output(chunk)
                            }
                        }
                        val result = CommandResult(process.waitFor(), transcript.toString())
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        process.destroy()
                    }
                }
            }
        }
    }

    suspend fun downloadCacheBytes(): Long {
        val cache = File(context.cacheDir, "runtime-download")
        return if (cache.isDirectory && !Files.isSymbolicLink(cache.toPath())) StorageUsage.measure(cache) else 0L
    }

    fun clearDownloadCache() {
        PrivateFiles.deleteTree(File(context.cacheDir, "runtime-download"))
    }
}
