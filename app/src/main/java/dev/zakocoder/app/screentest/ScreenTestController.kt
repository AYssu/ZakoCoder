package dev.zakocoder.app.screentest

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.zakocoder.app.R
import dev.zakocoder.app.projects.ApkInstallCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Owns target identity and evidence. Model tools cannot select a target or grant access. */
class ScreenTestController(context: Context, private val projectRoot: (String) -> File) {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(ScreenTestState())
    val state: StateFlow<ScreenTestState> = mutableState.asStateFlow()
    private val epoch = AtomicLong()
    private val selectionLock = Any()
    private val actionMutex = Mutex()
    private val reportMutex = Mutex()
    private val reportQueueLock = Any()
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reportTail: Deferred<Unit>? = null
    @Volatile private var selected: Selection? = null
    @Volatile private var operation: Job? = null
    private val rootBackend = RootScreenTestBackend(this.context)
    private var overlayReturn: (String, String) -> Unit = { _, _ -> }
    private var overlayStop: (String, String) -> Unit = { project, lease -> stopOverlayTask(project, lease) }
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay = ScreenTestOverlay(this.context,
        { visible -> mutableState.update { it.copy(overlayVisible = visible) } },
        { project, lease ->
            if (taskLease(project) == lease) {
                try { overlayReturn(project, lease) } finally { setOverlayEnabled(false) }
            }
        }, { project, lease -> if (taskLease(project) == lease) overlayStop(project, lease) },
        { code -> mutableState.update { it.copy(overlayEnabled = false, overlayFailure = code, overlayGranted = Settings.canDrawOverlays(this.context)) } })
    fun stopOverlayTask(expectedProject: String, expectedLease: String): Boolean = synchronized(selectionLock) {
        if (selected?.projectId != expectedProject || selected?.sessionId != expectedLease) false
        else { stopWithReason("overlay_stop"); true }
    }
    fun bindOverlayActions(returnToApp: (String, String) -> Unit, stopTask: (String, String) -> Unit) {
        overlayReturn = returnToApp; overlayStop = stopTask
    }
    fun reportOverlayServiceFailure() {
        mutableState.update { it.copy(overlayEnabled = false, overlayFailure = "foreground_service") }
    }
    fun setOverlayEnabled(enabled: Boolean) {
        mutableState.update { it.copy(overlayEnabled = enabled && Settings.canDrawOverlays(this.context), overlayFailure = "") }
        refresh()
    }
    fun openOverlaySettings() {
        this.context.startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${this.context.packageName}".toUri()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private val connectionCallback: () -> Unit = ::refresh

    init {
        ScreenTestAccessibilityService.connectionListeners += java.lang.ref.WeakReference(connectionCallback)
        overlayScope.launch {
            state.collect { current -> overlay.update(current.overlayEnabled && current.overlayGranted && current.active && !current.busy, current.projectId, current.projectId?.let(::taskLease)) }
        }
        refresh()
    }

    fun refresh() {
        val component = ComponentName(context, ScreenTestAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        mutableState.update { it.copy(enabled = enabled, connected = ScreenTestAccessibilityService.isConnected(),
            rootGranted = rootBackend.granted, installAllowed = context.packageManager.canRequestPackageInstalls(),
            overlayGranted = Settings.canDrawOverlays(context)) }
    }

    fun setAccess(access: ScreenTestAccess) {
        if (mutableState.value.access == access) return
        stopWithReason("access_changed")
        mutableState.update { it.copy(access = access) }
    }

    suspend fun requestRootAccess(): Boolean {
        check(mutableState.value.access == ScreenTestAccess.Root)
        val granted = rootBackend.requestAccess()
        refresh()
        return granted
    }

    fun setScreenshotSharing(projectId: String, enabled: Boolean) {
        synchronized(selectionLock) {
            check(selected?.projectId == projectId && mutableState.value.active) { message(R.string.screen_test_need_selection) }
            mutableState.update { it.copy(shareScreenshots = enabled) }
        }
    }

    fun stop() = stopWithReason("user_stop")

    private fun stopWithReason(reason: String) {
        val running = synchronized(selectionLock) {
            epoch.incrementAndGet()
            val ended = selected
            selected = null
            if (ended != null) enqueueReport(ended, "stop", true, reason.take(80))
            val running = operation
            operation = null
            mutableState.update { it.copy(packageName = null, apkPath = null, active = false,
                busy = false, shareScreenshots = false, summary = message(R.string.screen_test_stopped)) }
            ScreenTestAccessibilityService.instance?.stop()
            rootBackend.stop()
            overlayScope.launch { overlay.remove() }
            running
        }
        running?.cancel()
    }

    suspend fun discoverApks(projectId: String): List<ScreenTestApk> = withContext(Dispatchers.IO) {
        val root = projectRoot(projectId)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val boundary = root.canonicalFile.toPath()
        val results = mutableListOf<ScreenTestApk>()
        var count = 0
        val job = currentCoroutineContext()[Job]
        fun visit(directory: File, depth: Int) {
            if (depth > 16 || count >= 20_000 || results.size >= 40) return
            directory.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                job?.ensureActive()
                if (++count > 20_000 || results.size >= 40 || Files.isSymbolicLink(file.toPath())) return@forEach
                if (!file.canonicalFile.toPath().startsWith(boundary)) return@forEach
                if (file.isDirectory && file.name !in setOf(".git", ".gradle", "node_modules")) visit(file, depth + 1)
                else if (file.isFile && file.extension.equals("apk", true) && file.length() in 1..MAX_APK_BYTES) {
                    val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    val info = archiveInfo(file) ?: return@forEach
                    if (isAllowed(info)) results += descriptor(info, relative)
                }
            }
        }
        // Java directory traversal is bounded; interruption is checked at every entry.
        visit(root, 0)
        results
    }

    suspend fun selectApk(projectId: String, relativePath: String): ScreenTestApk = actionMutex.withLock {
        stopWithReason("target_reselected")
        val generation = epoch.get()
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            val copy = ApkInstallCopy(File(context.cacheDir, "apk-install")).prepare(projectRoot(projectId), relativePath) {
                job?.ensureActive()
            }
            try {
                currentCoroutineContext().ensureActive()
                val info = archiveInfo(copy) ?: error(message(R.string.screen_test_invalid_apk))
                check(isAllowed(info)) { message(R.string.screen_test_invalid_apk) }
                val signerSet = signatures(info)
                check(signerSet.isNotEmpty()) { message(R.string.screen_test_invalid_apk) }
                val installed = installedInfo(info.packageName)
                if (installed != null) check(isAllowed(installed) && signatures(installed) == signerSet) {
                    message(R.string.screen_test_changed_identity)
                }
                check(epoch.get() == generation) { message(R.string.screen_test_stopped) }
                val apk = descriptor(info, relativePath)
                val selection = Selection(projectId, apk, signerSet, artifactDigest(copy), UUID.randomUUID().toString())
                report(selection, "select", true, "selected_debug_apk")
                synchronized(selectionLock) {
                    check(epoch.get() == generation) { message(R.string.screen_test_stopped) }
                    selected = selection
                    mutableState.update { it.copy(projectId = projectId, packageName = apk.packageName, apkPath = relativePath,
                        active = true, shareScreenshots = false, summary = message(R.string.screen_test_ready),
                        reportPath = reportFile(projectId).absolutePath) }
                }
                apk
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw safeError(error)
            } finally { copy.delete() }
        }
    }

    fun taskLease(projectId: String): String? = synchronized(selectionLock) {
        selected?.takeIf { it.projectId == projectId && mutableState.value.active }?.sessionId
    }

    suspend fun execute(projectId: String, args: JSONObject, expectedSession: String? = null): ScreenTestResult = actionMutex.withLock {
        coroutineScope {
            val action = args.optString("action")
            require(action in ACTIONS) { "Unknown screen-test action" }
            if (action == "status") return@coroutineScope try { status(projectId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { throw safeError(error) }
            val job = currentCoroutineContext()[Job]
            val (selection, generation) = synchronized(selectionLock) {
                val value = selected ?: throw ScreenTestException(message(R.string.screen_test_need_selection))
                if (value.projectId != projectId || !mutableState.value.active) throw ScreenTestException(message(R.string.screen_test_need_selection))
                if (expectedSession != null && value.sessionId != expectedSession) throw ScreenTestException(message(R.string.screen_test_lease_changed))
                operation = job
                mutableState.update { it.copy(busy = true) }
                value to epoch.get()
            }
            try {
                withTimeout(20_000) {
                    overlay.hideForAction()
                    val result = withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        check(epoch.get() == generation && selected === selection) { message(R.string.screen_test_stopped) }
                        when (action) {
                            "finish" -> ScreenTestResult("{\"finished\":true}")
                            "install" -> install(selection)
                            "launch" -> launch(selection)
                            else -> {
                                verifyInstalled(selection)
                                when (mutableState.value.access) {
                                    ScreenTestAccess.Normal -> error(message(R.string.screen_test_need_accessibility))
                                    ScreenTestAccess.Accessibility -> {
                                        val service = ScreenTestAccessibilityService.instance
                                        check(service != null && mutableState.value.enabled) { message(R.string.screen_test_need_accessibility) }
                                        service.execute(selection.apk.packageName, args)
                                    }
                                    ScreenTestAccess.Root -> {
                                        check(rootBackend.granted) { "Root access must be explicitly authorized by the user" }
                                        if (action == "screenshot") {
                                            val service = ScreenTestAccessibilityService.instance
                                            check(service != null && mutableState.value.enabled) {
                                                message(R.string.screen_test_root_screenshot_accessibility)
                                            }
                                            service.execute(selection.apk.packageName, args)
                                        } else rootBackend.execute(selection.apk.packageName, args)
                                    }
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    check(epoch.get() == generation && selected === selection) { message(R.string.screen_test_stopped) }
                    check(result.output.length <= 64_000 && result.output.toByteArray(Charsets.UTF_8).size <= 64_000) {
                        message(R.string.screen_test_action_failed)
                    }
                    withContext(Dispatchers.IO) {
                        report(selection, action, true, if (action == "assert_text") "assertion_passed" else "request_completed",
                            result.imageUri?.lastPathSegment)
                    }
                    mutableState.update { it.copy(summary = result.output.take(800)) }
                    if (action == "finish") { operation = null; stopWithReason("tool_finished") }
                    result
                }
            } catch (error: TimeoutCancellationException) {
                ScreenTestAccessibilityService.instance?.stop()
                rootBackend.stop()
                reportCancellation(selection, action, "timeout", error)
                throw ScreenTestException(message(R.string.screen_test_timeout), error)
            } catch (cancelled: CancellationException) {
                reportCancellation(selection, action, "cancelled", cancelled)
                throw cancelled
            } catch (error: Exception) {
                val safe = safeError(error)
                withContext(Dispatchers.IO) { report(selection, action, false, safe.message.orEmpty()) }
                mutableState.update { it.copy(summary = safe.message.orEmpty()) }
                throw safe
            } finally {
                operation = null
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    suspend fun finishTask(projectId: String) = finishTask(projectId, taskLease(projectId))

    suspend fun finishTask(projectId: String, lease: String?) {
        if (lease == null) return
        val selection = synchronized(selectionLock) {
            val value = selected ?: return
            if (value.projectId != projectId || value.sessionId != lease) return
            stopWithReason("task_finished")
            value
        }
        withContext(Dispatchers.IO) { report(selection, "finish", true, "task_ended") }
    }

    suspend fun readReport(projectId: String): String = withContext(Dispatchers.IO) {
        try { synchronized(reportQueueLock) { reportTail }?.await() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ScreenTestException(message(R.string.screen_test_report_unavailable)) }
        reportMutex.withLock {
            val file = reportFile(projectId)
            if (!file.isFile) return@withLock message(R.string.screen_test_no_report)
            check(file.length() <= MAX_REPORT_BYTES)
            AtomicFile(file).openRead().bufferedReader().use { it.readText() }
        }
    }

    private suspend fun status(projectId: String): ScreenTestResult = withContext(Dispatchers.IO) {
        refresh()
        val (initialSelection, generation) = synchronized(selectionLock) { selected to epoch.get() }
        val selection = initialSelection?.takeIf { it.projectId == projectId }
        val installed = selection?.let { installedInfo(it.apk.packageName) }
        val matching = selection != null && installed != null && isAllowed(installed) && signatures(installed) == selection.signatures
        val installedDigest = if (matching) artifactDigest(File(requireNotNull(installed?.applicationInfo?.sourceDir))) else null
        val projectDigest = selection?.let { projectArtifactDigest(it) }
        val artifactMatches = matching && installedDigest == selection?.artifactDigest && projectDigest == selection?.artifactDigest
        synchronized(selectionLock) {
            check(epoch.get() == generation && selected === initialSelection) { message(R.string.screen_test_stopped) }
            val current = state.value
            ScreenTestResult(JSONObject().put("selected", selection != null).put("package", selection?.apk?.packageName)
                .put("installed", installed != null).put("identityMatches", matching).put("artifactMatches", artifactMatches)
                .put("selectedApkSha256", selection?.artifactDigest).put("installedApkSha256", installedDigest).put("projectApkSha256", projectDigest)
                .put("access", current.access.name).put("accessibilityEnabled", current.enabled)
                .put("accessibilityConnected", current.connected).put("rootGranted", current.rootGranted)
                .put("shareScreenshots", current.shareScreenshots).put("active", selection != null).toString())
        }
    }

    private suspend fun install(selection: Selection): ScreenTestResult {
        check(context.packageManager.canRequestPackageInstalls()) { message(R.string.screen_test_install_permission) }
        // A rebuild may change bytes, but cannot change the package, signer, or debug-only boundary.
        val job = currentCoroutineContext()[Job]
        val copy = ApkInstallCopy(File(context.cacheDir, "apk-install")).prepare(projectRoot(selection.projectId), selection.apk.relativePath) {
            job?.ensureActive()
        }
        var handedOff = false
        try {
            val info = archiveInfo(copy) ?: error(message(R.string.screen_test_invalid_apk))
            check(isAllowed(info) && info.packageName == selection.apk.packageName && signatures(info) == selection.signatures) {
                message(R.string.screen_test_changed_identity)
            }
            val installed = installedInfo(selection.apk.packageName)
            if (installed != null) check(isAllowed(installed) && signatures(installed) == selection.signatures) {
                message(R.string.screen_test_changed_identity)
            }
            val digest = artifactDigest(copy)
            currentCoroutineContext().ensureActive()
            check(selected === selection) { message(R.string.screen_test_stopped) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.apk-files", copy)
            withContext(Dispatchers.Main.immediate) {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("", uri) })
            }
            handedOff = true
            selection.artifactDigest = digest
            return ScreenTestResult(message(R.string.screen_test_install_pending))
        } finally { if (!handedOff) copy.delete() }
    }

    private suspend fun launch(selection: Selection): ScreenTestResult {
        verifyInstalled(selection)
        val intent = context.packageManager.getLaunchIntentForPackage(selection.apk.packageName)
            ?: error("Selected Debug APK has no launcher activity")
        check(intent.component?.packageName == selection.apk.packageName) { message(R.string.screen_test_changed_identity) }
        currentCoroutineContext().ensureActive()
        check(selected === selection) { message(R.string.screen_test_stopped) }
        withContext(Dispatchers.Main.immediate) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return ScreenTestResult(message(R.string.screen_test_launch_requested))
    }

    private suspend fun verifyInstalled(selection: Selection) {
        val info = installedInfo(selection.apk.packageName) ?: error(message(R.string.screen_test_not_installed))
        check(isAllowed(info) && signatures(info) == selection.signatures) { message(R.string.screen_test_changed_identity) }
        check(projectArtifactDigest(selection) == selection.artifactDigest &&
            artifactDigest(File(requireNotNull(info.applicationInfo?.sourceDir))) == selection.artifactDigest) {
            message(R.string.screen_test_artifact_changed)
        }
        val again = installedInfo(selection.apk.packageName)
        check(again?.applicationInfo?.sourceDir == info.applicationInfo?.sourceDir &&
            signatures(requireNotNull(again)) == selection.signatures) { message(R.string.screen_test_artifact_changed) }
    }

    private suspend fun projectArtifactDigest(selection: Selection): String {
        val root = projectRoot(selection.projectId)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val boundary = root.canonicalFile.toPath()
        fun source(): File {
            var file = root
            for (part in selection.apk.relativePath.split('/')) {
                file = File(file, part)
                require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(boundary))
            }
            return file
        }
        val file = source()
        val digest = artifactDigest(file)
        require(source().canonicalFile == file.canonicalFile)
        return digest
    }

    /** Hash immutable APK bytes, not versionCode: rebuilding with the same version is still distinct. */
    private suspend fun artifactDigest(file: File): String {
        currentCoroutineContext().ensureActive()
        require(Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && file.length() in 1..MAX_APK_BYTES)
        val size = file.length()
        val modified = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_APK_BYTES)
                digest.update(buffer, 0, count)
            }
        }
        require(total == size && file.length() == size && file.lastModified() == modified)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(file.absolutePath,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(packageName: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun isAllowed(info: PackageInfo): Boolean {
        val app = info.applicationInfo ?: return false
        // A single selected APK cannot attest to separately installed split code/resources.
        return info.splitNames.isNullOrEmpty() && app.splitSourceDirs.isNullOrEmpty() &&
            info.packageName != context.packageName && info.packageName !in setOf("android", "com.android.systemui") &&
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
            app.uid != android.os.Process.myUid()
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certificates.orEmpty().map { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun descriptor(info: PackageInfo, relative: String) = ScreenTestApk(relative, info.packageName,
        info.packageName, info.versionName.orEmpty(), info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    private fun reportFile(projectId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "screen-test/reports/$key.jsonl")
    }

    private suspend fun reportCancellation(selection: Selection, action: String, detail: String, cancelled: CancellationException) {
        try {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { report(selection, action, false, detail) }
        } catch (reportFailure: Exception) {
            // Audit persistence must not turn a user's cancellation into a different failure.
            if (reportFailure !== cancelled) cancelled.addSuppressed(reportFailure)
        }
    }

    private suspend fun report(selection: Selection, action: String, passed: Boolean, detail: String, evidence: String? = null) {
        enqueueReport(selection, action, passed, detail, evidence).await()
    }

    /** Enqueue in call order. In particular, user stop never waits on disk on the main thread. */
    private fun enqueueReport(selection: Selection, action: String, passed: Boolean, detail: String, evidence: String? = null): Deferred<Unit> {
        val outcome = when {
            !passed -> "failed"
            action == "install" -> "awaiting_user_install_confirmation"
            action in setOf("launch", "click", "long_click", "input_text", "back", "swipe") -> "requested_observe_to_verify"
            else -> "completed"
        }
        val entry = JSONObject().put("time", System.currentTimeMillis()).put("timeIso", java.time.Instant.now().toString())
            .put("session", selection.sessionId).put("package", selection.apk.packageName).put("apkSha256", selection.artifactDigest)
            .put("action", action).put("outcome", outcome).put("operationSucceeded", passed)
            .put("assertionPassed", if (action == "assert_text") passed else null)
            .put("detail", detail.take(300)).put("evidence", evidence).toString()
        return synchronized(reportQueueLock) {
            val previous = reportTail?.takeUnless { it.isCompleted }
            reportScope.async {
                previous?.join()
                writeReport(selection.projectId, entry)
            }.also { next ->
                reportTail = next
                next.invokeOnCompletion { failure ->
                    if (failure != null) mutableState.update { it.copy(summary = message(R.string.screen_test_report_unavailable)) }
                }
            }
        }
    }

    private suspend fun writeReport(projectId: String, entry: String) {
        reportMutex.withLock {
            val file = reportFile(projectId)
            check(file.parentFile?.let { it.mkdirs() || it.isDirectory } == true) { message(R.string.screen_test_report_unavailable) }
            val previous = if (file.isFile && file.length() <= MAX_REPORT_BYTES) AtomicFile(file).openRead().bufferedReader().use { it.readLines() }.takeLast(199) else emptyList()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write((previous + entry).joinToString("\n", postfix = "\n").toByteArray())
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
        }
    }

    private fun safeError(error: Exception): ScreenTestException {
        if (error is ScreenTestException) return error
        val known = listOf(R.string.screen_test_need_selection, R.string.screen_test_need_accessibility,
            R.string.screen_test_wrong_foreground, R.string.screen_test_invalid_apk, R.string.screen_test_changed_identity,
            R.string.screen_test_not_installed, R.string.screen_test_install_permission, R.string.screen_test_stale_snapshot,
            R.string.screen_test_sensitive, R.string.screen_test_action_failed, R.string.screen_test_stopped,
            R.string.screen_test_assert_failed, R.string.screen_test_screenshot_unsupported, R.string.screen_test_report_unavailable,
            R.string.screen_test_root_screenshot_accessibility, R.string.screen_test_secure_screenshot,
            R.string.screen_test_screenshot_failed, R.string.screen_test_screenshot_interval, R.string.screen_test_screenshot_overlay,
            R.string.screen_test_artifact_changed, R.string.screen_test_lease_changed)
            .map(::message)
        return ScreenTestException(error.message?.takeIf { it in known } ?: message(R.string.screen_test_action_failed), error)
    }

    private fun message(id: Int) = context.getString(id)
    private data class Selection(val projectId: String, val apk: ScreenTestApk, val signatures: Set<String>,
        @Volatile var artifactDigest: String, val sessionId: String)

    companion object {
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val MAX_REPORT_BYTES = 512L * 1024
        private val ACTIONS = setOf("status", "install", "launch", "observe", "click", "long_click", "swipe", "input_text", "back", "assert_text", "screenshot", "finish")
    }
}
