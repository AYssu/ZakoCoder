package dev.zakocoder.app.device

import android.content.Context
import android.content.Intent
import dev.zakocoder.app.screentest.protectedDeviceScreenPackage
import dev.zakocoder.app.screentest.resolvedPermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class LaunchableDeviceApp(val packageName: String, val label: String)

internal data class LaunchableDeviceAppPage(
    val apps: List<LaunchableDeviceApp>,
    val matchedCount: Int,
) {
    val truncated: Boolean get() = apps.size < matchedCount
}

internal const val DEVICE_APP_QUERY_MAX = 200
internal const val DEVICE_APP_PAGE_MAX = 100
internal const val DEVICE_APP_RESPONSE_MAX_BYTES = 32_768

/** Only publicly launchable apps; never enumerate permissions, accounts or installed APK files. */
@Suppress("DEPRECATION")
internal suspend fun launchableDeviceApps(context: Context): List<LaunchableDeviceApp> = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val pm = context.packageManager
    val permissionController = resolvedPermissionController(context)
    val resolved = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    currentCoroutineContext().ensureActive()
    resolved.mapNotNull { candidate ->
        currentCoroutineContext().ensureActive()
        val activity = candidate.activityInfo ?: return@mapNotNull null
        val packageName = activity.packageName
        if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled ||
            protectedDeviceScreenPackage(packageName, context.packageName, permissionController)) return@mapNotNull null
        LaunchableDeviceApp(packageName, candidate.loadLabel(pm).toString())
    }.let { selectLaunchableDeviceApps(it, "", Int.MAX_VALUE).apps }
}

/** Stable bounded discovery lets the model narrow a large list without a manual app picker. */
internal fun selectLaunchableDeviceApps(
    source: List<LaunchableDeviceApp>,
    query: String,
    limit: Int = DEVICE_APP_PAGE_MAX,
): LaunchableDeviceAppPage {
    require(query.length <= DEVICE_APP_QUERY_MAX && query.none(Char::isISOControl)) { "virtual_display_app_query_invalid" }
    require(limit > 0)
    val normalized = query.trim()
    val matches = source.asSequence()
        .filter { it.packageName.length <= 255 }
        .map { it.copy(label = boundedDeviceAppLabel(it.label).ifBlank { it.packageName }) }
        .filter { normalized.isBlank() || it.packageName.contains(normalized, ignoreCase = true) || it.label.contains(normalized, ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .distinctBy { it.packageName }
        .toList()
    return LaunchableDeviceAppPage(matches.take(limit), matches.size)
}

/** Keep JSON complete under a UTF-8 budget; a byte slice could corrupt names and the envelope. */
internal fun launchableDeviceAppsResponse(page: LaunchableDeviceAppPage, displayId: Int): String {
    requireVirtualDisplayId(displayId)
    val apps = JSONArray()
    var recordBytes = 0
    for (app in page.apps.take(DEVICE_APP_PAGE_MAX)) {
        val record = JSONObject().put("label", app.label).put("package_name", app.packageName)
        val size = record.toString().toByteArray(Charsets.UTF_8).size + 1
        // Reserve the envelope, separators and metadata without truncating serialized JSON.
        if (recordBytes + size > DEVICE_APP_RESPONSE_MAX_BYTES - 1024) break
        apps.put(record)
        recordBytes += size
    }
    return JSONObject().put("apps", apps).put("matched_count", page.matchedCount)
        .put("returned_count", apps.length()).put("truncated", apps.length() < page.matchedCount)
        .put("limit", DEVICE_APP_PAGE_MAX).put("display_id", displayId).toString()
        .also { check(it.toByteArray(Charsets.UTF_8).size <= DEVICE_APP_RESPONSE_MAX_BYTES) }
}

internal fun boundedDeviceAppLabel(label: String): String {
    val clean = label.filterNot(Char::isISOControl)
    val end = minOf(clean.length, 160).let { length ->
        if (length < clean.length && length > 0 && clean[length - 1].isHighSurrogate() && clean[length].isLowSurrogate()) length - 1 else length
    }
    return clean.substring(0, end)
}
