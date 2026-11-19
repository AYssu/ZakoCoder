package dev.zakocoder.app.screentest

import android.content.Context
import android.util.AtomicFile
import dev.zakocoder.app.R
import dev.zakocoder.app.device.DeviceAccessController
import dev.zakocoder.app.device.DeviceMode
import dev.zakocoder.app.device.VirtualScreenController
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Owns explicit task authorization and evidence. Capability availability never grants a task. */
class ScreenTestController(context: Context,
    private val virtualScreen: VirtualScreenController, private val deviceAccess: DeviceAccessController) {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(ScreenTestState())
    val state: StateFlow<ScreenTestState> = mutableState.asStateFlow()
    private val epoch = AtomicLong()
    private val selectionLock = Any()
    private val actionMutex = Mutex()
    private val actionFeedback = ScreenActionFeedback()
    private val reportMutex = Mutex()
    private val reportQueueLock = Any()
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reportTail: Deferred<Unit>? = null
    @Volatile private var selected: Selection? = null
    @Volatile private var operation: Job? = null
    // State emissions may occur under another controller's lock. Dispatch collectors so
    // they cannot synchronously re-enter selectionLock with the opposite lock order.
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        lifecycleScope.launch {
            deviceAccess.state.collect {
                stopWithReason("device_permission_changed", onlyIfInvalid = true)
                refresh()
            }
        }
        lifecycleScope.launch {
            virtualScreen.state.collect {
                stopWithReason("virtual_display_closed", onlyIfInvalid = true)
                refresh()
            }
        }
        refresh()
    }

    fun refresh() {
        val permissions = deviceAccess.state.value
        mutableState.update { it.copy(enabled = permissions.mode == DeviceMode.Root && permissions.rootGranted,
            connected = virtualScreen.state.value.active, rootGranted = permissions.rootGranted) }
    }

    fun setScreenshotSharing(projectId: String, enabled: Boolean, expectedLease: String? = null) {
        synchronized(selectionLock) {
            check(selected?.let { it.projectId == projectId && mutableState.value.active && virtualLeaseValid(it) } == true) { message(R.string.screen_test_device_task_required) }
            check(expectedLease == null || selected?.sessionId == expectedLease) { message(R.string.screen_test_lease_changed) }
            mutableState.update { it.copy(shareScreenshots = enabled) }
        }
    }

    fun stop() = stopWithReason("user_stop")

    private fun stopWithReason(reason: String, onlyIfInvalid: Boolean = false) {
        val running = synchronized(selectionLock) {
            // Notifications can outlive the task that emitted them. Recheck the currently
            // selected lease under its lock, so an old event cannot stop a new valid task.
            if (onlyIfInvalid && selected?.let { !virtualLeaseValid(it) } != true) return
            epoch.incrementAndGet()
            val ended = selected
            selected = null
            if (ended != null) enqueueReport(ended, "stop", true, reason.take(80))
            val running = operation
            operation = null
            mutableState.update { it.copy(packageName = null, taskId = null, leaseId = null, active = false,
                busy = false, shareScreenshots = false,
                overlayProgress = ScreenOverlayProgress(ScreenOverlayPhase.Cancelled, title = message(R.string.screen_overlay_progress_stopped)), summary = message(R.string.screen_test_stopped)) }
            virtualScreen.stop()
            actionFeedback.clear()
            running
        }
        running?.cancel()
    }

    /** Called only after an explicit user decision for this exact task, never from capability enable. */
    suspend fun authorizeDeviceTask(projectId: String, taskId: String): String = actionMutex.withLock { coroutineScope {
        require(projectId.isNotBlank() && taskId.isNotBlank())
        stopWithReason("task_reauthorized")
        val authorizationJob = currentCoroutineContext()[Job]
        val generation = synchronized(selectionLock) {
            operation = authorizationJob
            epoch.get()
        }
        val deviceGeneration: Long
        val displayId: Int
        try {
            run {
                val permissions = deviceAccess
                check(permissions.state.value.loaded && permissions.state.value.mode == DeviceMode.Root) { message(R.string.virtual_screen_root_required) }
                deviceGeneration = permissions.generation
                currentCoroutineContext().ensureActive()
                virtualScreen.start()
                currentCoroutineContext().ensureActive()
                displayId = requireNotNull(virtualScreen.state.value.displayId)
                check(displayId > 0 && permissions.generation == deviceGeneration && epoch.get() == generation) { message(R.string.screen_test_stopped) }
            }
            val selection = Selection(projectId, sessionId = UUID.randomUUID().toString(), taskId = taskId,
                rootGeneration = deviceGeneration, displayId = displayId)
            withContext(Dispatchers.IO) { report(selection, "authorize", true,
                "virtual_display_task_authorized") }
            synchronized(selectionLock) {
                check(epoch.get() == generation) { message(R.string.screen_test_stopped) }
                check(virtualLeaseValid(selection)) { message(R.string.screen_test_lease_changed) }
                selected = selection
                mutableState.update { it.copy(projectId = projectId, packageName = null,
                    taskId = taskId, leaseId = selection.sessionId, active = true, shareScreenshots = false,
                    overlayProgress = ScreenOverlayProgress(title = message(R.string.screen_test_device_ready)),
                    summary = message(R.string.screen_test_device_ready), reportPath = reportFile(projectId).absolutePath) }
            }
            selection.sessionId
        } catch (failure: Exception) {
            if (epoch.get() == generation) stopWithReason("authorization_failed")
            throw failure
        } finally {
            synchronized(selectionLock) { if (operation === authorizationJob) operation = null }
        }
    } }

    fun taskLease(projectId: String): String? = synchronized(selectionLock) {
        selected?.takeIf { it.projectId == projectId && mutableState.value.active && virtualLeaseValid(it) }?.sessionId
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
                val value = selected ?: throw ScreenTestException(message(R.string.screen_test_device_task_required))
                check(virtualLeaseValid(value)) { message(R.string.screen_test_lease_changed) }
                if (value.projectId != projectId || !mutableState.value.active)
                    throw ScreenTestException(message(R.string.screen_test_device_task_required))
                if (expectedSession != null && value.sessionId != expectedSession) throw ScreenTestException(message(R.string.screen_test_lease_changed))
                operation = job
                mutableState.update { it.copy(busy = true, overlayProgress = overlayProgress(action, ScreenOverlayPhase.Verifying)) }
                value to epoch.get()
            }
            var stage = "access"
            try {
                withTimeout(screenActionTimeoutMillis(action)) {
                    stage = "foreground"
                    val result = withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        check(epoch.get() == generation && selected === selection) { message(R.string.screen_test_stopped) }
                        check(deviceAccess.state.value.mode == DeviceMode.Root && deviceAccess.state.value.rootGranted && virtualLeaseValid(selection)) {
                            message(R.string.virtual_screen_root_required)
                        }
                        if (action == "screenshot") check(mutableState.value.shareScreenshots) { message(R.string.virtual_screen_sharing_required) }
                        when (action) {
                            "finish" -> ScreenTestResult("{\"finished\":true}")
                            else -> {
                                stage = "root_virtual_$action"
                                synchronized(selectionLock) {
                                    check(selected === selection && virtualLeaseValid(selection)) { message(R.string.screen_test_lease_changed) }
                                    mutableState.update { it.copy(overlayProgress = overlayProgress(action, ScreenOverlayPhase.Acting)) }
                                }
                                // Discovery is read-only; it needs no node baseline or gesture receipt.
                                if (action == "list_apps") virtualScreen.execute("", args)
                                else actionFeedback.execute("${selection.sessionId}:virtual:${selection.displayId}", args) { request ->
                                    check(virtualLeaseValid(selection)) { message(R.string.screen_test_lease_changed) }
                                    virtualScreen.execute("", request)
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    check(epoch.get() == generation && selected === selection) { message(R.string.screen_test_stopped) }
                    check(result.output.length <= 64_000 && result.output.toByteArray(Charsets.UTF_8).size <= 64_000) {
                        message(R.string.screen_test_action_failed)
                    }
                    selection.currentPackage = result.packageName
                    withContext(Dispatchers.IO) {
                        val output = if (action in setOf("click", "long_click", "input_text", "swipe", "scroll", "back", "wait_until", "assert_text"))
                            JSONObject(result.output) else JSONObject()
                        val verified = output.optBoolean("verified")
                        report(selection, action, action != "wait_until" || verified,
                            if (action == "assert_text") "assertion_passed" else if (action == "wait_until") {
                                if (verified) "condition_observed" else "condition_unverified"
                            } else "request_completed", result.imageUri?.lastPathSegment,
                            output.takeIf { it.has("dispatched") })
                    }
                    synchronized(selectionLock) {
                        if (epoch.get() == generation && selected === selection) mutableState.update {
                            val passed = action != "wait_until" || JSONObject(result.output).optBoolean("verified")
                            it.copy(packageName = result.packageName ?: it.packageName, summary = result.output.take(800),
                                overlayProgress = overlayProgress(action, if (passed) ScreenOverlayPhase.Succeeded else ScreenOverlayPhase.Failed))
                        }
                    }
                    if (action == "finish") { operation = null; stopWithReason("tool_finished") }
                    result
                }
            } catch (error: TimeoutCancellationException) {
                val detail = context.getString(R.string.screen_test_stage_timeout, screenTestStageLabel(context, stage), screenActionTimeoutMillis(action) / 1000)
                reportCancellation(selection, action, "timeout:$stage", error)
                synchronized(selectionLock) {
                    if (selected === selection) {
                        // Revoke this lease without turning an action timeout into cancellation
                        // of its caller. The model receives the concrete failure, then must ask
                        // for a fresh device-task grant before another screen operation.
                        operation = null
                        stopWithReason("operation_timeout")
                        mutableState.update { it.copy(summary = detail,
                            overlayProgress = overlayProgress(action, ScreenOverlayPhase.Failed)) }
                    }
                }
                throw ScreenTestException(detail, error)
            } catch (cancelled: CancellationException) {
                reportCancellation(selection, action, "cancelled", cancelled)
                if (selected === selection) stopWithReason("operation_cancelled")
                synchronized(selectionLock) {
                    if (epoch.get() == generation && selected === selection) mutableState.update {
                        it.copy(overlayProgress = overlayProgress(action, ScreenOverlayPhase.Cancelled))
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                val safe = safeError(error)
                withContext(Dispatchers.IO) { report(selection, action, false,
                    (safe.cause as? ScreenTestTimeoutDiagnostic)?.let { "timeout:${it.stage}; " }.orEmpty() + safe.message.orEmpty()) }
                synchronized(selectionLock) {
                    if (epoch.get() == generation && selected === selection) mutableState.update {
                        it.copy(summary = safe.message.orEmpty(), overlayProgress = overlayProgress(action, ScreenOverlayPhase.Failed))
                    }
                }
                throw safe
            } finally {
                synchronized(selectionLock) {
                    if (epoch.get() == generation && selected === selection) {
                        operation = null
                        mutableState.update { it.copy(busy = false) }
                    }
                }
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
        val selection = initialSelection?.takeIf { it.projectId == projectId && state.value.active && virtualLeaseValid(it) }
        synchronized(selectionLock) {
            check(epoch.get() == generation && selected === initialSelection) { message(R.string.screen_test_stopped) }
            val current = state.value
            ScreenTestResult(JSONObject().put("scope", "virtual_display")
                .put("display_id", virtualScreen.state.value.displayId).put("physical_screen_control", false)
                .put("taskAuthorizationRequired", selection == null)
                .put("active", selection != null).put("taskId", selection?.taskId).put("lease", selection?.sessionId)
                .put("access", "Root").put("rootModeReady", current.enabled)
                .put("virtualDisplayReady", current.connected).put("rootGranted", current.rootGranted)
                .put("shareScreenshots", selection != null && current.shareScreenshots).toString())
        }
    }

    private fun overlayProgress(action: String, phase: ScreenOverlayPhase): ScreenOverlayProgress {
        val actionLabel = message(when (action) {
            "launch" -> R.string.screen_overlay_action_launch
            "list_apps" -> R.string.screen_task_action_list_apps
            "observe" -> R.string.screen_overlay_action_observe
            "click", "long_click" -> R.string.screen_overlay_action_click
            "swipe", "scroll" -> R.string.screen_overlay_action_swipe
            "input_text" -> R.string.screen_overlay_action_input
            "back" -> R.string.screen_overlay_action_back
            "assert_text", "wait_until" -> R.string.screen_overlay_action_assert
            "screenshot" -> R.string.screen_overlay_action_screenshot
            else -> R.string.screen_overlay_action_finish
        })
        val title = context.getString(when (phase) {
            ScreenOverlayPhase.Verifying -> R.string.screen_overlay_progress_verifying
            ScreenOverlayPhase.Acting -> R.string.screen_overlay_progress_acting
            ScreenOverlayPhase.Succeeded -> R.string.screen_overlay_progress_succeeded
            ScreenOverlayPhase.Failed -> R.string.screen_overlay_progress_failed
            ScreenOverlayPhase.Cancelled -> R.string.screen_overlay_progress_cancelled
            ScreenOverlayPhase.Ready -> R.string.screen_overlay_progress_ready
        }, actionLabel)
        val detail = if (phase == ScreenOverlayPhase.Succeeded) when (action) {
            "launch" -> message(R.string.screen_test_launch_requested)
            else -> ""
        } else ""
        return ScreenOverlayProgress(phase, action, title, detail)
    }

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

    private suspend fun report(selection: Selection, action: String, passed: Boolean, detail: String, evidence: String? = null, feedback: JSONObject? = null) {
        enqueueReport(selection, action, passed, detail, evidence, feedback).await()
    }

    /** Enqueue in call order. In particular, user stop never waits on disk on the main thread. */
    private fun enqueueReport(selection: Selection, action: String, passed: Boolean, detail: String, evidence: String? = null, feedback: JSONObject? = null): Deferred<Unit> {
        val outcome = when {
            !passed -> "failed"
            action in setOf("launch", "click", "long_click", "input_text", "back", "swipe", "scroll") -> "requested_observe_to_verify"
            else -> "completed"
        }
        val entry = JSONObject().put("time", System.currentTimeMillis()).put("timeIso", java.time.Instant.now().toString())
            .put("session", selection.sessionId).put("taskId", selection.taskId).put("scope", "virtual_display")
            .put("displayId", selection.displayId)
            .put("package", selection.currentPackage)
            .put("action", action).put("outcome", outcome).put("operationSucceeded", passed)
            .put("assertionPassed", if (action in setOf("assert_text", "wait_until")) passed else null)
            .put("detail", detail.take(300)).put("evidence", evidence).apply {
                // Only fixed receipt fields enter audit, never text, snapshots or node contents.
                if (feedback != null) {
                    put("dispatched", feedback.getBoolean("dispatched"))
                    put("observed_change", feedback.opt("observed_change") ?: JSONObject.NULL)
                    put("verified", feedback.getBoolean("verified"))
                    put("unknown_reason", feedback.opt("unknown_reason") ?: JSONObject.NULL)
                }
            }.toString()
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
        val known = listOf(R.string.screen_test_stale_snapshot, R.string.screen_test_sensitive,
            R.string.screen_test_action_failed, R.string.screen_test_stopped, R.string.screen_test_assert_failed,
            R.string.screen_test_screenshot_unsupported, R.string.screen_test_report_unavailable,
            R.string.screen_test_secure_screenshot, R.string.screen_test_screenshot_failed,
            R.string.screen_test_screenshot_interval, R.string.screen_test_lease_changed,
            R.string.screen_test_device_task_required, R.string.screen_test_device_ready,
            R.string.virtual_screen_root_required, R.string.virtual_screen_sharing_required)
            .map(::message)
        return ScreenTestException(error.message?.takeIf { it in known } ?: message(R.string.screen_test_action_failed), error)
    }

    private fun virtualLeaseValid(selection: Selection): Boolean {
        val permissions = deviceAccess
        val display = virtualScreen.state.value
        return selection.rootGeneration == permissions.generation && permissions.state.value.mode == DeviceMode.Root &&
            permissions.state.value.rootGranted && display.active && selection.displayId > 0 && selection.displayId == display.displayId
    }

    private fun message(id: Int) = context.getString(id)
    private data class Selection(val projectId: String, val sessionId: String, val taskId: String,
        @Volatile var currentPackage: String? = null, val rootGeneration: Long, val displayId: Int)

    companion object {
        const val DEVICE_TASK_OWNER = "device-screen"
        private const val MAX_REPORT_BYTES = 512L * 1024
        private val ACTIONS = setOf("status", "list_apps", "launch", "observe", "click", "long_click", "swipe", "scroll", "input_text", "back", "assert_text", "wait_until", "screenshot", "finish")
    }
}

/** Static stage codes are retained in local audit records; UI labels never expose command text. */
internal class ScreenTestTimeoutDiagnostic(val stage: String) : RuntimeException()

internal fun screenTestStageLabel(context: Context, stage: String): String = context.getString(when {
    stage == "overlay_sync" -> R.string.screen_test_stage_frame
    stage == "artifact_verification" -> R.string.screen_test_stage_artifact
    stage == "foreground" -> R.string.screen_test_stage_foreground
    stage == "overlay" -> R.string.screen_test_stage_occlusion
    stage == "hierarchy_dump" -> R.string.screen_test_stage_nodes
    stage == "input" -> R.string.screen_test_stage_input
    stage == "access" -> R.string.screen_test_stage_access
    stage.startsWith("accessibility_") -> R.string.screen_test_stage_accessibility
    stage.startsWith("root_") -> R.string.screen_test_stage_root
    else -> R.string.screen_test_stage_operation
})
