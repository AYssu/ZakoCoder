package dev.zakocoder.app.updates

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.zakocoder.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Owns update IO, while Compose only renders state and dispatches explicit actions. */
class AppUpdates internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val client: ReleaseClient, preferencesName: String) {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope, ReleaseClient(), "app_updates")
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val apk = File(context.filesDir, "app-updates/update.apk")
    private var work: Job? = null
    private var nextAttempt = 0L
    private var verifiedHash: String? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val writeLock = kotlinx.coroutines.sync.Mutex()
    init {
        scope.launch {
            val initial = withContext(Dispatchers.IO) {
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                val defaultChannel = if (ReleaseVersion.parse(version)?.preview != null) UpdateChannel.Beta else UpdateChannel.Stable
                val channel = runCatching { UpdateChannel.valueOf(preferences.getString("channel", defaultChannel.name)!!) }.getOrDefault(defaultChannel)
                UpdateState(initialized = true, currentVersion = version, channel = channel, automatic = preferences.getBoolean("automatic", true))
            }
            val recovered = withContext(Dispatchers.IO) { restore(initial) }
            mutable.value = recovered
            check(false)
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) scope.launch { check(false) }
                }
            }
            try {
                connectivity.registerDefaultNetworkCallback(callback); networkCallback = callback
                scope.coroutineContext[Job]?.invokeOnCompletion { runCatching { connectivity.unregisterNetworkCallback(callback) } }
            }
            catch (_: SecurityException) { /* Foreground checks still work. */ }
        }
    }
    fun configure(automatic: Boolean = state.value.automatic, channel: UpdateChannel = state.value.channel) {
        if (!state.value.initialized || work?.isActive == true || state.value.phase == UpdatePhase.Downloading) return
        val channelChanged = channel != state.value.channel
        mutable.update { if (channelChanged) it.copy(automatic = automatic, channel = channel, phase = UpdatePhase.Idle,
            release = null, prompt = false, error = null, pendingInstall = false)
            else it.copy(automatic = automatic) }
        scope.launch { writeLock.lock(); try {
            withContext(Dispatchers.IO) {
                persist {
                    putBoolean("automatic", automatic).putString("channel", channel.name)
                    if (channelChanged) remove("ready").remove("pending_install")
                }
            }
            if (channelChanged) check(true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.update_settings_failed) } }
        finally { writeLock.unlock() } }
    }
    /** Called on foreground/network recovery; failed attempts back off and never display an error popup. */
    fun check(manual: Boolean = true) {
        if (!state.value.initialized || work?.isActive == true || state.value.phase == UpdatePhase.Downloading) return
        if (ReleaseVersion.parse(state.value.currentVersion) == null) {
            if (manual) mutable.update { it.copy(phase = UpdatePhase.Failed, error = R.string.update_dev_version, release = null, prompt = false) }
            return
        }
        if (!manual && !state.value.automatic) return
        if (!manual && System.currentTimeMillis() < nextAttempt) return
        if (state.value.phase == UpdatePhase.Ready) return
        work = scope.launch {
            if (!manual) {
                val due = withContext(Dispatchers.IO) { System.currentTimeMillis() - preferences.getLong("last_attempt", 0) >= 24 * 60 * 60 * 1000L }
                if (!due) return@launch
            }
            val channel = state.value.channel
            mutable.update { it.copy(phase = UpdatePhase.Checking, error = null) }
            try {
                nextAttempt = System.currentTimeMillis() + 5 * 60 * 1000L
                val releases = withContext(Dispatchers.IO) { client.releases() }
                withContext(Dispatchers.IO) { persist { putLong("last_attempt", System.currentTimeMillis()) } }
                val candidate = selectRelease(releases, state.value.currentVersion, channel)
                val remind = candidate != null && withContext(Dispatchers.IO) {
                    manual || preferences.getString("dismissed", "") != candidate.tag ||
                        System.currentTimeMillis() - preferences.getLong("dismissed_at", 0) > 7 * 24 * 60 * 60 * 1000L
                }
                mutable.update { it.copy(phase = if (candidate == null) UpdatePhase.Current else UpdatePhase.Available,
                    release = candidate, prompt = !manual && remind) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(phase = UpdatePhase.Failed, error = R.string.update_check_failed, prompt = false) } }
        }
    }
    fun dismissPrompt() {
        val tag = state.value.release?.tag ?: return
        mutable.update { it.copy(prompt = false) }
        scope.launch {
            try { withContext(Dispatchers.IO) { persist { putString("dismissed", tag).putLong("dismissed_at", System.currentTimeMillis()) } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.update_settings_failed) } }
        }
    }
    fun download() {
        val release = state.value.release ?: return
        if (work?.isActive == true) return
        mutable.update { it.copy(phase = UpdatePhase.Downloading, received = 0, total = release.apk.size, error = null, prompt = false) }
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    kotlin.check(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
                    apk.parentFile!!.mkdirs()
                    client.download(release, apk) { received, total -> mutable.update { it.copy(received = received, total = total) } }
                    verifiedHash = updateSha256(apk)
                    validateApk(apk, release)
                    saveReady(release)
                }
                mutable.update { it.copy(phase = UpdatePhase.Ready) }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { apk.delete() }
                mutable.update { it.copy(phase = UpdatePhase.Available, pendingInstall = false) }
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { apk.delete() }
                mutable.update { it.copy(phase = UpdatePhase.Failed, error = R.string.update_download_failed, pendingInstall = false) }
            }
        }
    }
    fun cancelDownload() { if (state.value.phase == UpdatePhase.Downloading) { work?.cancel(); client.cancel() } }
    fun install() {
        if (state.value.phase != UpdatePhase.Ready || work?.isActive == true) return
        work = scope.launch {
            try {
                val release = state.value.release ?: return@launch
                try { withContext(Dispatchers.IO) { validateApk(apk, release) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    mutable.update { it.copy(phase = UpdatePhase.Failed, error = R.string.update_download_failed, pendingInstall = false) }
                    withContext(Dispatchers.IO) {
                        apk.delete(); persist { remove("ready").remove("pending_install") }
                    }
                    return@launch
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    withContext(Dispatchers.IO) { persist { putBoolean("pending_install", true) } }
                    mutable.update { it.copy(pendingInstall = true) }
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    withContext(Dispatchers.IO) { persist { putBoolean("pending_install", false) } }
                    mutable.update { it.copy(pendingInstall = false) }
                    val uri = withContext(Dispatchers.IO) { FileProvider.getUriForFile(context, "${context.packageName}.apk-files", apk) }
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.update_install_failed, pendingInstall = false) } }
        }
    }
    fun resumed() {
        if (state.value.pendingInstall) {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else mutable.update { it.copy(pendingInstall = false, error = R.string.update_install_permission) }
        } else check(false)
    }
    private fun saveReady(release: AppRelease) {
        val json = org.json.JSONObject().put("tag", release.tag).put("notes", release.notes).put("preview", release.prerelease)
            .put("name", release.apk.name).put("url", release.apk.url).put("size", release.apk.size).put("hash", verifiedHash)
            .put("sums_url", release.sums.url).put("sums_size", release.sums.size)
        persist { putString("ready", json.toString()) }
    }
    private fun restore(initial: UpdateState): UpdateState {
        return try {
            val json = org.json.JSONObject(preferences.getString("ready", null) ?: return initial)
            val release = AppRelease(json.getString("tag"), json.getString("notes"), json.getBoolean("preview"),
                ReleaseAsset(json.getString("name"), json.getString("url"), json.getLong("size")),
                ReleaseAsset("SHA256SUMS.txt", json.getString("sums_url"), json.getLong("sums_size")))
            verifiedHash = json.getString("hash").also { kotlin.check(it.matches(Regex("[0-9a-f]{64}"))) }
            validateApk(apk, release)
            initial.copy(phase = UpdatePhase.Ready, release = release, pendingInstall = preferences.getBoolean("pending_install", false))
        } catch (_: Exception) {
            val cleared = runCatching { persist { remove("ready").remove("pending_install") } }.isSuccess
            apk.delete()
            if (cleared) initial else initial.copy(error = R.string.update_settings_failed)
        }
    }
    // KTX edit(commit = true) discards the Boolean result. Update/installation state
    // must advance only after durable persistence succeeds; all callers run on IO.
    @android.annotation.SuppressLint("UseKtx")
    private fun persist(change: android.content.SharedPreferences.Editor.() -> Unit) {
        val editor = preferences.edit().apply(change)
        kotlin.check(editor.commit()) { "Update preferences could not be saved" }
    }
    private fun validateApk(file: File, release: AppRelease) = validateUpdateApk(context, file, release, verifiedHash)

}
