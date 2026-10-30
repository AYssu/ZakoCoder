package dev.zakocoder.app.ssh

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Native SSH client. Model commands run exclusively in the selected REMOTE account, never on Android. */
class SshController(context: Context) {
    private val store = SshStore(context.applicationContext)
    private val mutableState = MutableStateFlow(SshState())
    val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private val epoch = AtomicLong()
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<Session>()
    private val sockets = mutableMapOf<Session, Socket>()
    private var pending: Pair<SshProfile, SshHostKey>? = null
    private val configurationLock = Any()
    private val configurationVersions = mutableMapOf<String, Pair<SshProfile, String>>()

    /** Opaque, credential-free authorization identity. Missing while configuration is being changed. */
    fun configurationLease(id: String): String? = synchronized(configurationLock) {
        configurationVersions[id]?.takeIf { (profile, _) -> mutableState.value.profiles.find { it.id == id } == profile }?.second
    }
    private fun invalidateConfiguration(id: String) {
        synchronized(configurationLock) { configurationVersions.remove(id) }
        stop()
    }

    /** Invalidates both queued operations and live sockets; tmux sessions deliberately survive. */
    fun stop() {
        epoch.incrementAndGet()
        closeConnections()
        mutableState.update { it.copy(busy = false, activeProfileId = null) }
    }
    fun disconnect() = stop()

    private fun closeConnections() {
        val (ownedSockets, ownedSessions) = synchronized(sessionsLock) {
            (sockets.values.toList() to sessions.toList()).also { sockets.clear(); sessions.clear() }
        }
        // Closing the socket also interrupts the pre-authentication connect/handshake phase,
        // where JSch Session.disconnect() alone can be a no-op.
        ownedSockets.forEach { socket -> try { socket.close() } catch (_: java.io.IOException) { /* Already closed. */ } }
        ownedSessions.forEach(Session::disconnect)
    }

    suspend fun load() = guarded { publish(store.read()) }

    suspend fun saveProfile(profile: SshProfile, password: String? = null, privateKey: String? = null, passphrase: String? = null) {
        SshSafety.validate(profile)
        SshSafety.check(password == null || password.length <= 8192, "credential_limit")
        SshSafety.check(privateKey == null || privateKey.length <= 128 * 1024, "credential_limit")
        SshSafety.check(passphrase == null || passphrase.length <= 8192, "credential_limit")
        invalidateConfiguration(profile.id)
        guarded {
            val rows = store.read()
            val previous = rows.find { it.profile.id == profile.id }
            val prior = previous?.takeIf { it.profile.auth == profile.auth && it.profile.host == profile.host && it.profile.port == profile.port && it.profile.username == profile.username }?.credential
            val credential = if (profile.auth == SshAuth.Password) SshCredentials(password = password ?: prior?.password.orEmpty())
                else SshCredentials(privateKey = privateKey ?: prior?.privateKey.orEmpty(), passphrase = passphrase ?: prior?.passphrase.orEmpty())
            val trusted = previous?.profile?.takeIf { it.host == profile.host && it.port == profile.port }?.hostFingerprint
            val next = profile.copy(hostFingerprint = trusted,
                hasCredential = if (profile.auth == SshAuth.Password) credential.password.isNotEmpty() else credential.privateKey.isNotEmpty())
            val sameIdentity = previous?.profile?.let { it.host == profile.host && it.port == profile.port && it.username == profile.username } == true
            val updated = rows.filterNot { it.profile.id == profile.id } + SshStore.Entry(next, credential, if (sameIdentity) previous!!.tasks else emptySet())
            store.write(updated)
            pending = null
            publish(updated)
        }
    }

    suspend fun removeProfile(id: String) {
        invalidateConfiguration(id)
        guarded { val rows = store.read().filterNot { it.profile.id == id }; store.write(rows); pending = null; publish(rows) }
    }

    /** Stops at host-key verification. No password, key, agent, or keyboard-interactive authentication is attempted. */
    suspend fun probeHost(id: String): SshHostKey = guarded(id) { token ->
        val profile = entry(id).profile
        val repository = PinnedHostRepository(profile, null)
        val jsch = JSch().apply { setHostKeyRepository(repository) }
        val session = session(jsch, profile, token)
        try {
            try { session.connect(15_000) } catch (error: Exception) {
                if (repository.observed == null) throw SshException("connection_failed", error)
            }
            ensureCurrent(token)
            val observed = repository.observed ?: throw SshException("host_key_missing")
            pending = profile to observed
            mutableState.update { it.copy(pendingHostKey = observed) }
            observed
        } finally { release(session) }
    }

    /** Only accepts the exact pending UI probe for the unchanged saved endpoint. */
    suspend fun trustHost(id: String, fingerprint: String) {
        invalidateConfiguration(id)
        guarded {
            val candidate = pending ?: throw SshException("host_key_probe_required")
            val rows = store.read()
            val current = rows.find { it.profile.id == id } ?: throw SshException("profile_missing")
            SshSafety.check(candidate.first == current.profile && candidate.second.profileId == id &&
                candidate.second.fingerprint == fingerprint, "host_key_probe_expired")
            val updated = rows.map { if (it.profile.id == id) it.copy(profile = it.profile.copy(hostFingerprint = fingerprint)) else it }
            store.write(updated)
            pending = null
            publish(updated)
        }
    }

    suspend fun forgetHost(id: String) {
        invalidateConfiguration(id)
        guarded {
            val rows = store.read().map { if (it.profile.id == id) it.copy(profile = it.profile.copy(hostFingerprint = null)) else it }
            store.write(rows)
            pending = null
            publish(rows)
        }
    }

    suspend fun execute(profileId: String, args: JSONObject, expectedConfiguration: String? = null): JSONObject = guarded(profileId) { token ->
        if (expectedConfiguration != null) SshSafety.check(configurationLease(profileId) == expectedConfiguration, "configuration_changed")
        val entry = entry(profileId)
        val profile = entry.profile
        SshSafety.check(profile.hostFingerprint != null, "host_key_untrusted")
        SshSafety.check(profile.hasCredential, "credential_missing")
        val session = authenticated(entry, token)
        try {
            ensureCurrent(token)
            val result = when (val action = args.getString("action")) {
                "status" -> {
                    val result = exec(session, profile, "command -v tmux >/dev/null 2>&1", 15, token)
                    JSONObject().put("connected", true).put("trusted", true).put("tmux_enabled", profile.tmuxEnabled)
                        .put("tmux_available", result.getInt("exit_code") == 0)
                }
                "exec" -> exec(session, profile, command(args), timeout(args), token)
                "list_files", "read_file", "write_file", "edit_file" -> files(session, profile, action, args, token)
                "tmux_run", "tmux_list", "tmux_capture", "tmux_input", "tmux_close" -> tmux(session, profile, action, args, token)
                else -> throw SshException("unsupported_action")
            }
            ensureCurrent(token)
            result
        } finally { release(session) }
    }

    private fun entry(id: String) = store.read().find { it.profile.id == id } ?: throw SshException("profile_missing")
    private fun publish(rows: List<SshStore.Entry>) {
        synchronized(configurationLock) {
            configurationVersions.keys.retainAll(rows.map { it.profile.id }.toSet())
            rows.forEach { row -> configurationVersions.getOrPut(row.profile.id) { row.profile to UUID.randomUUID().toString() } }
        }
        mutableState.update { it.copy(profiles = rows.map { row -> row.profile }, pendingHostKey = pending?.second, error = null) }
    }
    private fun ensureCurrent(token: Long) { if (epoch.get() != token) throw CancellationException("SSH operation revoked") }

    private suspend fun <T> guarded(profile: String? = null, block: (Long) -> T): T {
        val token = epoch.get()
        return operations.withLock {
            ensureCurrent(token)
            mutableState.update { it.copy(busy = true, activeProfileId = profile, error = null) }
            try {
                coroutineScope {
                    val worker = async(Dispatchers.IO) { ensureCurrent(token); block(token) }
                    try { worker.await() } catch (cancelled: CancellationException) {
                        if (epoch.compareAndSet(token, token + 1)) {
                            closeConnections()
                        }
                        throw cancelled
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                ensureCurrent(token)
                val safe = if (error is SshException) error else SshException("operation_failed", error)
                if (epoch.get() == token) mutableState.update { it.copy(error = safe.code) }
                throw safe
            } finally {
                mutableState.update { it.copy(busy = false, activeProfileId = null) }
            }
        }
    }

    private fun session(jsch: JSch, profile: SshProfile, token: Long): Session {
        val session = jsch.getSession(profile.username, profile.host, profile.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "none")
        session.timeout = 15_000
        session.serverAliveInterval = 10_000
        session.serverAliveCountMax = 1
        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val socket = Socket()
                synchronized(sessionsLock) { ensureCurrent(token); sockets[session] = socket }
                try {
                    val address = InetSocketAddress(host, port)
                    ensureCurrent(token)
                    socket.connect(address, 15_000)
                    ensureCurrent(token)
                    return socket
                } catch (error: Exception) {
                    socket.close()
                    synchronized(sessionsLock) { sockets.remove(session) }
                    throw error
                }
            }
            override fun getInputStream(socket: Socket): InputStream { ensureCurrent(token); return socket.getInputStream() }
            override fun getOutputStream(socket: Socket): java.io.OutputStream { ensureCurrent(token); return socket.getOutputStream() }
        })
        synchronized(sessionsLock) { ensureCurrent(token); sessions += session }
        return session
    }
    private fun authenticated(entry: SshStore.Entry, token: Long): Session {
        val repository = PinnedHostRepository(entry.profile, entry.profile.hostFingerprint)
        val jsch = JSch().apply { setHostKeyRepository(repository) }
        val secret = entry.credential
        if (entry.profile.auth == SshAuth.PrivateKey) {
            val bytes = secret.privateKey.toByteArray()
            val passphrase = secret.passphrase.toByteArray()
            try { jsch.addIdentity("selected-profile", bytes, null, passphrase) }
            catch (error: Exception) { throw SshException("private_key_invalid", error) }
            finally { bytes.fill(0); passphrase.fill(0) }
        }
        val session = session(jsch, entry.profile, token)
        try {
            if (entry.profile.auth == SshAuth.Password) {
                session.setConfig("PreferredAuthentications", "password")
                val password = secret.password.toByteArray()
                try { session.setPassword(password) } finally { password.fill(0) }
            } else session.setConfig("PreferredAuthentications", "publickey")
            session.connect(15_000)
            ensureCurrent(token)
            return session
        } catch (error: Exception) {
            release(session)
            ensureCurrent(token)
            throw SshException(if (repository.observed?.fingerprint != null && repository.observed?.fingerprint != entry.profile.hostFingerprint)
                "host_key_changed" else "connection_or_auth_failed", error)
        } finally { jsch.removeAllIdentity() }
    }
    private fun release(session: Session) {
        val socket = synchronized(sessionsLock) { sessions -= session; sockets.remove(session) }
        try { socket?.close() } catch (_: java.io.IOException) { /* Preserve operation/cancellation result. */ }
        session.disconnect()
    }

    private fun command(args: JSONObject): String = args.getString("command").also {
        SshSafety.check(it.isNotBlank() && it.length <= 32 * 1024 && '\u0000' !in it, "invalid_command")
    }
    private fun timeout(args: JSONObject): Int = args.optInt("timeout_seconds", 30).also { SshSafety.check(it in 1..120, "invalid_timeout") }

    private fun exec(session: Session, profile: SshProfile, command: String, timeout: Int, token: Long): JSONObject {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setPty(false)
        channel.setInputStream(null)
        channel.setCommand("cd " + SshSafety.quote(profile.workingDirectory) + " && " + command)
        val output = channel.inputStream
        val error = channel.errStream
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var truncated = false
        val deadline = System.nanoTime() + timeout * 1_000_000_000L
        fun drain(input: InputStream, target: ByteArrayOutputStream) {
            val buffer = ByteArray(8192)
            while (input.available() > 0) {
                ensureCurrent(token)
                val count = input.read(buffer, 0, minOf(buffer.size, input.available()))
                if (count < 0) return
                val remaining = SshSafety.MAX_BYTES - stdout.size() - stderr.size()
                if (count > remaining) truncated = true
                if (remaining > 0) target.write(buffer, 0, minOf(count, remaining))
                if (System.nanoTime() >= deadline) throw SshException("command_timeout")
            }
        }
        try {
            channel.connect(minOf(timeout * 1000, 15_000))
            while (true) {
                ensureCurrent(token)
                drain(output, stdout)
                drain(error, stderr)
                if (channel.isClosed && output.available() == 0 && error.available() == 0) break
                if (System.nanoTime() >= deadline) throw SshException("command_timeout")
                Thread.sleep(15)
            }
            return JSONObject().put("stdout", stdout.toString("UTF-8")).put("stderr", stderr.toString("UTF-8"))
                .put("exit_code", channel.exitStatus).put("truncated", truncated)
        } finally { channel.disconnect() }
    }

    private fun files(session: Session, profile: SshProfile, action: String, args: JSONObject, token: Long): JSONObject {
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(15_000)
            val root = channel.realpath(profile.workingDirectory).trimEnd('/').ifEmpty { "/" }
            val relative = SshSafety.relativePath(args.optString("path", ""), action == "list_files")
            val absolute = if (relative.isEmpty()) root else root.trimEnd('/') + "/" + relative
            val literal = SshSafety.literalSftpPath(absolute)
            validateParents(channel, root, relative, token)
            if (action == "list_files") {
                SshSafety.check(channel.lstat(literal).isDir, "not_directory")
                val rows = JSONArray()
                var truncated = false
                var listedBytes = 0
                channel.ls(literal, ChannelSftp.LsEntrySelector { item ->
                    ensureCurrent(token)
                    if (item.filename !in setOf(".", "..")) {
                        val itemBytes = item.filename.toByteArray().size + 160
                        if (rows.length() >= 200 || listedBytes + itemBytes > SshSafety.MAX_BYTES) {
                            truncated = true
                            return@LsEntrySelector ChannelSftp.LsEntrySelector.BREAK
                        }
                        listedBytes += itemBytes
                        rows.put(JSONObject().put("name", item.filename.take(4096)).put("directory", item.attrs.isDir)
                            .put("symlink", item.attrs.isLink).put("size", item.attrs.size))
                    }
                    ChannelSftp.LsEntrySelector.CONTINUE
                })
                return JSONObject().put("path", relative).put("entries", rows).put("truncated", truncated)
            }
            val before = attrsOrNull(channel, literal)
            SshSafety.check(before == null || (!before.isLink && before.isReg), "not_regular_file")
            if (action == "read_file") {
                SshSafety.check(before != null, "file_missing")
                val offset = args.optLong("offset", 0)
                val limit = args.optInt("limit", SshSafety.MAX_BYTES)
                SshSafety.check(offset >= 0 && limit in 1..SshSafety.MAX_BYTES, "invalid_range")
                val complete = if (before!!.size <= SshSafety.MAX_FILE_BYTES) channel.get(literal).use { readComplete(it, token) } else null
                val bytes = if (complete != null) {
                    if (offset >= complete.size) byteArrayOf() else SshSafety.utf8Page(complete, offset.toInt(), limit)
                } else {
                    val chunk = channel.get(literal, null, offset).use { readBounded(it, limit + 3, token) }
                    SshSafety.utf8Page(chunk, 0, limit)
                }
                return JSONObject().put("path", relative).put("content", bytes.toString(Charsets.UTF_8))
                    .put("encoding", "utf-8").put("offset_bytes", offset).put("next_offset_bytes", offset + bytes.size)
                    .put("size", before!!.size).put("truncated", offset + bytes.size < before.size)
                    .put("editable", complete != null)
                    .also { if (complete != null) it.put("sha256", SshSafety.sha(complete)) }
            }
            val expected = args.optString("expected_sha256").takeIf { it.isNotEmpty() }
            SshSafety.check(expected == null || expected.matches(Regex("[a-fA-F0-9]{64}")), "invalid_digest")
            val previous = before?.let {
                SshSafety.check(it.size <= SshSafety.MAX_FILE_BYTES, "file_too_large")
                channel.get(literal).use { input -> readComplete(input, token) }
            }
            if (previous != null) SshSafety.check(expected != null && SshSafety.sha(previous).equals(expected, true), "file_changed")
            else SshSafety.check(expected == null && action != "edit_file", "file_missing")
            val content = if (action == "edit_file") {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val original = try { decoder.decode(ByteBuffer.wrap(previous!!)).toString() } catch (error: Exception) { throw SshException("not_utf8", error) }
                SshSafety.replaceExactlyOnce(original, args.getString("old_text"), args.getString("new_text"))
            } else args.getString("content")
            val bytes = content.toByteArray()
            SshSafety.check(bytes.size <= SshSafety.MAX_FILE_BYTES, "file_too_large")
            // Remote SFTP has no compare-and-swap primitive. Recheck immediately before same-directory rename.
            val temp = absolute.substringBeforeLast('/') + "/.zako-write-" + UUID.randomUUID().toString()
            val tempLiteral = SshSafety.literalSftpPath(temp)
            try {
                channel.put(bytes.inputStream(), tempLiteral)
                channel.chmod(before?.permissions?.and(0x1ff) ?: 0x180, tempLiteral)
                validateParents(channel, root, relative, token)
                val current = attrsOrNull(channel, literal)
                SshSafety.check((before == null && current == null) || (previous != null && current != null && current.isReg && !current.isLink &&
                    current.size <= SshSafety.MAX_FILE_BYTES && channel.get(literal).use { SshSafety.sha(readComplete(it, token)) }.equals(expected, true)), "file_changed")
                ensureCurrent(token)
                if (before != null) SshSafety.check(channel.getExtension("posix-rename@openssh.com") != null, "atomic_replace_unavailable")
                channel.rename(tempLiteral, literal)
                return JSONObject().put("path", relative).put("bytes_written", bytes.size).put("sha256", SshSafety.sha(bytes))
                    .put("concurrent_write_guard", "best_effort")
            } finally {
                // Only this operation's random temporary file; never clean a user directory.
                try { channel.rm(tempLiteral) } catch (_: SftpException) { /* Renamed or connection closed. */ }
            }
        } finally { channel.disconnect() }
    }

    private fun validateParents(channel: ChannelSftp, root: String, relative: String, token: Long) {
        var current = root
        SshSafety.check(channel.lstat(SshSafety.literalSftpPath(root)).isDir, "not_directory")
        val parts = relative.split('/').filter { it.isNotEmpty() }
        for ((index, part) in parts.withIndex()) {
            ensureCurrent(token)
            current = current.trimEnd('/') + "/" + part
            val attrs = attrsOrNull(channel, SshSafety.literalSftpPath(current))
            if (index < parts.lastIndex) SshSafety.check(attrs != null && attrs.isDir && !attrs.isLink, "unsafe_path")
            else SshSafety.check(attrs == null || !attrs.isLink, "unsafe_path")
        }
    }
    private fun attrsOrNull(channel: ChannelSftp, path: String) = try { channel.lstat(path) } catch (error: SftpException) {
        if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw error
    }
    private fun readBounded(input: InputStream, limit: Int, token: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            ensureCurrent(token)
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun readComplete(input: InputStream, token: Long): ByteArray {
        val bytes = readBounded(input, SshSafety.MAX_FILE_BYTES + 1, token)
        SshSafety.check(bytes.size <= SshSafety.MAX_FILE_BYTES, "file_too_large")
        return bytes
    }

    private fun tmux(session: Session, profile: SshProfile, action: String, args: JSONObject, token: Long): JSONObject {
        SshSafety.check(profile.tmuxEnabled, "tmux_disabled")
        val socket = "zako_" + SshSafety.sha(profile.id.toByteArray()).take(12)
        val tmux = "tmux -L " + SshSafety.quote(socket)
        if (action == "tmux_run") {
            val id = UUID.randomUUID().toString().replace("-", "")
            val name = SshSafety.taskName(profile.id, id)
            // Register before launch: a dropped connection can leave a successfully created remote session.
            registerTask(profile.id, id, add = true)
            val script = "new-session -d -s " + SshSafety.quote(name) +
                " -c " + SshSafety.quote(profile.workingDirectory) + " " + SshSafety.quote(command(args)) +
                " \\; set-window-option -t " + SshSafety.quote(name + ":0") + " remain-on-exit on"
            val result = exec(session, profile, "$tmux $script", 15, token)
            return result.put("task_id", id).put("detached", result.getInt("exit_code") == 0)
        }
        val allowed = entry(profile.id).tasks
        if (action == "tmux_list") {
            val result = exec(session, profile, "$tmux list-sessions -F '#{session_name}'", 15, token)
            val names = result.getString("stdout").lineSequence().toSet()
            val rows = JSONArray()
            allowed.forEach { id -> if (SshSafety.taskName(profile.id, id) in names) rows.put(JSONObject().put("task_id", id)) }
            return JSONObject().put("tasks", rows).put("tmux_exit_code", result.getInt("exit_code"))
        }
        val id = args.getString("task_id")
        SshSafety.check(id in allowed, "task_not_owned")
        val target = SshSafety.quote(SshSafety.taskName(profile.id, id))
        val suffix = when (action) {
            "tmux_capture" -> {
                val limit = args.optInt("limit", 200)
                SshSafety.check(limit in 1..1000, "invalid_range")
                "capture-pane -p -t $target -S -$limit"
            }
            "tmux_input" -> {
                val text = args.optString("text", "")
                val key = args.optString("key", "")
                SshSafety.check(text.isNotEmpty() xor key.isNotEmpty(), "invalid_input")
                if (text.isNotEmpty()) {
                    SshSafety.check(text.length <= 8192 && '\u0000' !in text, "invalid_input")
                    "send-keys -t $target -l -- " + SshSafety.quote(text)
                } else {
                    SshSafety.check(key in setOf("Enter", "C-c", "C-d", "Escape", "Tab"), "invalid_input")
                    "send-keys -t $target " + SshSafety.quote(key)
                }
            }
            "tmux_close" -> "kill-session -t $target"
            else -> throw SshException("unsupported_action")
        }
        val result = exec(session, profile, "$tmux $suffix", 15, token).put("task_id", id)
        if (action == "tmux_close" && result.getInt("exit_code") == 0) registerTask(profile.id, id, add = false)
        return result
    }
    private fun registerTask(profileId: String, taskId: String, add: Boolean) {
        val rows = store.read().map { entry ->
            if (entry.profile.id != profileId) entry else {
                SshSafety.check(!add || entry.tasks.size < 64, "task_limit")
                entry.copy(tasks = if (add) entry.tasks + taskId else entry.tasks - taskId)
            }
        }
        store.write(rows)
    }

    private class PinnedHostRepository(private val profile: SshProfile, private val pinned: String?) : HostKeyRepository {
        var observed: SshHostKey? = null
            private set
        override fun check(host: String, key: ByteArray): Int {
            val observed = SshHostKey(profile.id, profile.host, profile.port, HostKey(host, key).type, SshSafety.fingerprint(key))
            this.observed = observed
            return if (pinned == null) HostKeyRepository.NOT_INCLUDED else if (pinned == observed.fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "selected-connection-pinned-key"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
