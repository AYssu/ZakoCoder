package dev.zakocoder.app.diagnostics

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.mcp.McpState
import dev.zakocoder.app.ssh.SshState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DoctorState(val busy: Boolean = false, val ready: Boolean = false,
    val error: Boolean = false, val warningCount: Int = 0)

internal data class DoctorInput(val ai: AiState, val workbench: WorkbenchState, val mcp: McpState, val ssh: SshState)

private val fatalMarkerFields = setOf("time", "exceptionClass", "thread", "stage")

/** Revalidates persisted crash markers because the file is outside the in-memory trust boundary. */
internal fun fatalMarkersReport(lines: List<String>): JSONArray {
    val markers = lines.mapNotNull(::validatedFatalMarker).takeLast(5)
    return JSONArray().apply { markers.forEach { put(it) } }
}

private fun validatedFatalMarker(line: String): JSONObject? = try {
    val marker = JSONObject(line)
    val keys = marker.keys().asSequence().toSet()
    val required = setOf("time", "exceptionClass", "thread")
    val time = marker.opt("time")
    val exceptionClass = marker.opt("exceptionClass")
    val thread = marker.opt("thread")
    val stage = marker.opt("stage")
    val valid = (keys == required || keys == fatalMarkerFields) &&
        time is Long && time >= 0 &&
        validFatalLabel(exceptionClass, 256) &&
        validFatalLabel(thread, 128, allowSpaces = true) &&
        (stage == null || validFatalLabel(stage, 64))
    marker.takeIf { valid }
} catch (_: Exception) {
    null
}

private fun validFatalLabel(value: Any?, maxLength: Int, allowSpaces: Boolean = false): Boolean =
    value is String && value.length <= maxLength && value.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character in "._-\$[]#()" || allowSpaces && character == ' '
    }

/** Local, user-initiated diagnostics. Does not probe services, load credentials or interrupt an active task. */
class DoctorController internal constructor(private val context: Context, private val snapshot: suspend () -> DoctorInput) {
    private val mutable = MutableStateFlow(DoctorState())
    val state = mutable.asStateFlow()
    private val operations = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val documents = DoctorDocuments(context)

    suspend fun prepare(): String = operation {
        val input = snapshot()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val warnings = mutableListOf<String>()
            val events = try { DiagnosticEvents.get(context).exportSnapshot() }
            catch (cancelled: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                warnings += "EVENT_SNAPSHOT_TIMEOUT"
                DiagnosticSnapshot("", 0, 0)
            }
            val system = system(warnings)
            val current = input.ai
            if (current.historyError) warnings += "HISTORY_ERROR"
            if (input.workbench.backgroundServiceError) warnings += "BACKGROUND_SERVICE_UNAVAILABLE"
            if (current.active) current.requests.lastOrNull()?.let { request ->
                if (!request.complete && request.failure == null && now - request.started - request.elapsed > 120_000)
                    warnings += "REQUEST_NO_RECORDED_PROGRESS_120S"
            }
            if (events.droppedEvents > 0 || events.writeFailures > 0) warnings += "EVENT_LOG_INCOMPLETE"
            val report = JSONObject().put("schemaVersion", 1).put("createdAt", now)
                .put("app", app()).put("system", system)
                .put("ai", DoctorReport.ai(current, now))
                .put("components", DoctorReport.components(input.workbench, input.mcp, input.ssh))
                .put("events", JSONObject().put("droppedThisProcess", events.droppedEvents)
                    .put("writeFailuresThisProcess", events.writeFailures).put("retentionDays", 7)
                    .put("maximumFilesBytes", 524288))
                .put("warnings", JSONArray(warnings))
                .put("scope", "Current conversation metadata and recent device-wide operational events; not a complete activity archive.")
            currentCoroutineContext().ensureActive()
            val token = documents.prepare(report.toString(2), events.jsonl)
            mutable.value = mutable.value.copy(ready = true, warningCount = warnings.size)
            token
        }
    }

    // Provider IO must not hold the preparation lock: a stalled cloud provider cannot block new snapshots.
    suspend fun writePrepared(uri: Uri, token: String) = documents.writePrepared(uri, token)
    suspend fun discard(token: String) { documents.discard(token); mutable.value = mutable.value.copy(ready = false) }
    fun discardLater(token: String) {
        cleanupScope.launch {
            try { discard(token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A cancelled export remains bounded and expires from the private cache. */ }
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = operations.withLock {
        mutable.value = mutable.value.copy(busy = true, error = false)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.value = mutable.value.copy(error = true); throw error }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }

    @Suppress("DEPRECATION")
    private fun app(): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().put("version", safeSystemText(info.versionName.orEmpty()))
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            .put("debuggable", context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    private fun system(warnings: MutableList<String>): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val result = JSONObject().put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", safeSystemText(Build.MANUFACTURER)).put("model", safeSystemText(Build.MODEL))
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.filter { it in setOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") }))
            .put("uptimeMs", SystemClock.uptimeMillis())
            .put("notificationsEnabled", notifications.areNotificationsEnabled())
            .put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("powerSaveMode", power.isPowerSaveMode).put("deviceIdleMode", power.isDeviceIdleMode)
            // prepare() invokes this method on Dispatchers.IO; FatalMarker reads only bounded files.
            .put("fatalMarkers", try {
                fatalMarkersReport(FatalMarker(File(context.filesDir, "diagnostics")).snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                JSONArray()
            })
        if (!notifications.areNotificationsEnabled()) warnings += "NOTIFICATIONS_DISABLED"
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) warnings += "BATTERY_OPTIMIZED"
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            result.put("network", JSONObject().put("present", capabilities != null)
                .put("internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                .put("wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                .put("vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true))
        } catch (_: SecurityException) { warnings += "NETWORK_STATE_UNAVAILABLE" }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val manager = context.getSystemService(ActivityManager::class.java)
                result.put("recentProcessExits", JSONArray(manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).map {
                    JSONObject().put("timestamp", it.timestamp).put("reason", it.reason).put("status", it.status)
                        .put("pssKiB", it.pss).put("rssKiB", it.rss)
                }))
            } catch (_: SecurityException) { warnings += "PROCESS_EXIT_INFO_UNAVAILABLE" }
        }
        return result
    }

    private fun safeSystemText(value: String): String = value.take(80).filter { it.isLetterOrDigit() || it in " ._-" }
}
