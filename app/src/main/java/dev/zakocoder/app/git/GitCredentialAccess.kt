package dev.zakocoder.app.git

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.shellArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files

/** PAT bytes cross a private FIFO; no plaintext token is persisted, including after process death. */
internal class GitCredentialAccess(private val store: GitCredentialStore, private val rootfs: File) {
    suspend fun execute(projectId: String, script: String, remoteUrl: String,
        execute: suspend (String, String) -> CommandResult,
    ): CommandResult = execute(projectId, script, remoteUrl) { id, guarded, _ -> execute(id, guarded) }

    /** Feed successive stdout chunks through redactChunk before publishing; append its result. */
    suspend fun execute(projectId: String, script: String, remoteUrl: String,
        execute: suspend (String, String, (String) -> String) -> CommandResult,
    ): CommandResult = withContext(Dispatchers.IO) {
        prepareRuntime(rootfs)
        val denyAskpass = "export GIT_ASKPASS=/bin/false SSH_ASKPASS=/bin/false;\n"
        // SSH authentication is deliberately outside the HTTPS PAT store.
        if (URI(remoteUrl).scheme != "https") return@withContext execute(projectId, denyAskpass + script) { it }
        gitCredentialBinding(projectId, remoteUrl)
        val credential = store.credential(projectId, remoteUrl)
            ?: return@withContext execute(projectId, denyAskpass + script) { it }
        val secrets = secretVariants(credential)
        withLease(credential, remoteUrl) { guestPath ->
            val stream = GitCredentialStreamRedactor(secrets)
            val result = execute(projectId, "export GIT_ASKPASS=${shellArgument(guestPath)} SSH_ASKPASS=/bin/false;\n$script", stream::accept)
            result.copy(output = secrets.fold(result.output) { output, secret -> output.replace(secret, "[redacted]") })
        }
    }

    internal suspend fun <T> withLease(credential: GitCredential, remoteUrl: String, block: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        credential.validate()
        val directory = synchronized(leaseLock) {
            val parent = authenticationParent(rootfs)
            sweep(parent)
            Files.createTempDirectory(parent.toPath(), "operation-").toFile().also {
                Os.chmod(it.absolutePath, 448)
                activeLeases.add(it.absolutePath)
            }
        }
        try {
            val user = File(directory, "username")
            Files.createFile(user.toPath()); Os.chmod(user.absolutePath, 384)
            user.writeText(credential.username + "\n")
            val token = File(directory, "token")
            Os.mkfifo(token.absolutePath, 384) // 0600; never fall back to a regular file.
            val guest = "/.zakocoder-git-auth/${directory.name}"
            val uri = URI(remoteUrl)
            val authority = uri.rawAuthority
            val path = gitCredentialPromptPath(uri.path.removePrefix("/"))
            val usernamePrompt = "Username for 'https://$authority/$path': "
            val passwordPrompt = "Password for 'https://${credential.username}@$authority/$path': "
            val helper = File(directory, "askpass")
            Files.createFile(helper.toPath()); Os.chmod(helper.absolutePath, 448)
            helper.writeText("#!/bin/sh\ncase \"\$1\" in\n" +
                "  ${shellArgument(usernamePrompt)}) exec /bin/cat ${shellArgument("$guest/username")} ;;\n" +
                "  ${shellArgument(passwordPrompt)}) IFS= read -r answer < ${shellArgument("$guest/token")} || exit 1; printf '%s\\n' \"\$answer\" ;;\n" +
                "  *) exit 1 ;;\nesac\n")
            coroutineScope {
                val writer = launch { supplyToken(token, credential.token) }
                try { block("$guest/askpass") } finally { writer.cancelAndJoin() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(leaseLock) {
                    try { cleanLease(directory) } finally { activeLeases.remove(directory.absolutePath) }
                }
            }
        }
    }

    /** Nonblocking IPC means cancellation and an operation that never asks for a password cannot hang. */
    private suspend fun supplyToken(fifo: File, token: String) {
        val bytes = (token + "\n").toByteArray(Charsets.UTF_8)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val descriptor = try {
                    Os.open(fifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.ENXIO) throw error
                    delay(20); continue
                }
                try {
                    require(OsConstants.S_ISFIFO(Os.fstat(descriptor).st_mode))
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        try { offset += Os.write(descriptor, bytes, offset, bytes.size - offset) }
                        catch (error: ErrnoException) {
                            if (error.errno == OsConstants.EPIPE) break
                            if (error.errno != OsConstants.EAGAIN) throw error
                            delay(10)
                        }
                    }
                } finally { Os.close(descriptor) }
                delay(20)
            }
        } finally { bytes.fill(0) }
    }

    companion object {
        private val leaseLock = Any()
        private val activeLeases = mutableSetOf<String>()

        /** Call before allowing any guest process after startup, including unauthenticated terminals.
         * Removes old-version plaintext remnants first. Live leases in this process are retained.
         * This is a single-app-process boundary, not isolation from arbitrary same-UID guest code.
         */
        suspend fun prepareRuntime(rootfs: File) = withContext(Dispatchers.IO) {
            synchronized(leaseLock) { sweep(authenticationParent(rootfs)) }
        }

        private fun authenticationParent(rootfs: File): File {
            require(!Files.isSymbolicLink(rootfs.toPath()))
            val canonicalRoot = rootfs.canonicalFile
            require(canonicalRoot.isDirectory)
            val parent = File(canonicalRoot, ".zakocoder-git-auth")
            require(!Files.isSymbolicLink(parent.toPath()))
            if (!parent.exists()) check(parent.mkdir())
            require(parent.isDirectory && parent.canonicalFile.parentFile == canonicalRoot)
            Os.chmod(parent.absolutePath, 448)
            return parent
        }
        private fun sweep(parent: File) {
            checkNotNull(parent.listFiles()).filter { it.name.startsWith("operation-") && it.absolutePath !in activeLeases }
                .forEach(::cleanLease)
        }
        private fun cleanLease(directory: File) {
            if (Files.isSymbolicLink(directory.toPath())) { Files.deleteIfExists(directory.toPath()); return }
            require(directory.isDirectory)
            // Remove the previous version's plaintext token before helper/username and other entries.
            listOf("token", "username", "askpass").forEach { Files.deleteIfExists(File(directory, it).toPath()) }
            checkNotNull(directory.listFiles()).forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(directory.toPath()) // No recursion; unexpected directories fail closed.
        }
    }
    private fun secretVariants(credential: GitCredential): List<String> = listOf(
        credential.token,
        java.net.URLEncoder.encode(credential.token, "UTF-8"),
        java.util.Base64.getEncoder().encodeToString("${credential.username}:${credential.token}".toByteArray()),
    ).distinct().sortedByDescending { it.length }
}

/** Hold only a suffix that could become a secret in the next chunk, rather than delaying all output. */
internal class GitCredentialStreamRedactor(private val secrets: List<String>) {
    private var pending = ""
    @Synchronized fun accept(chunk: String): String {
        val input = pending + chunk
        val output = StringBuilder()
        var index = 0
        pending = ""
        while (index < input.length) {
            val matched = secrets.firstOrNull { input.startsWith(it, index) }
            if (matched != null) {
                output.append("[redacted]"); index += matched.length; continue
            }
            val remaining = input.length - index
            if (secrets.any { remaining < it.length && it.regionMatches(0, input, index, remaining) }) {
                pending = input.substring(index); break
            }
            output.append(input[index++])
        }
        return output.toString()
    }
}

/** Git decodes URL path escapes, then percent-encodes all but RFC3986 unreserved characters and '/'. */
internal fun gitCredentialPromptPath(path: String): String = buildString {
    path.toByteArray(Charsets.UTF_8).forEach { byte ->
        val value = byte.toInt() and 255
        if (value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "-._~/") append(value.toChar())
        else { append('%'); append("0123456789ABCDEF"[value ushr 4]); append("0123456789ABCDEF"[value and 15]) }
    }
}
