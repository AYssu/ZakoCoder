package dev.zakocoder.app.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.zakocoder.app.screentest.ScreenTestAccessibilityService
import dev.zakocoder.app.settings.DevelopmentNotifications
import dev.zakocoder.app.settings.RunningNotificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppPermission { Notifications, Files, InstallPackages, Overlay, Accessibility, Battery }
data class AppPermissionStatus(val permission: AppPermission, val granted: Boolean?, val notificationStatus: RunningNotificationStatus? = null)

/** Read-only capability queries and explicitly requested system navigation. Never grants permissions. */
class AppPermissions(private val context: Context) {
    suspend fun snapshot(): List<AppPermissionStatus> = withContext(Dispatchers.IO) {
        AppPermission.entries.map { permission ->
            try {
                if (permission == AppPermission.Notifications) {
                    val status = DevelopmentNotifications(context).status()
                    AppPermissionStatus(permission, if (status == RunningNotificationStatus.Unknown) null else status == RunningNotificationStatus.Available, status)
                } else AppPermissionStatus(permission, when (permission) {
                    AppPermission.Notifications -> error("Handled above")
                    AppPermission.Files -> if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else
                        runtimePermissions(permission).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                    AppPermission.InstallPackages -> context.packageManager.canRequestPackageInstalls()
                    AppPermission.Overlay -> Settings.canDrawOverlays(context)
                    AppPermission.Accessibility -> Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                        ?.split(':')?.any { ComponentName.unflattenFromString(it) == ComponentName(context, ScreenTestAccessibilityService::class.java) } == true
                    AppPermission.Battery -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { AppPermissionStatus(permission, null) }
        }
    }
    fun runtimePermissions(permission: AppPermission): Array<String> = when {
        permission == AppPermission.Notifications && Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        permission == AppPermission.Files && Build.VERSION.SDK_INT < 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> emptyArray()
    }
    fun openSettings(permission: AppPermission): Boolean {
        if (permission == AppPermission.Notifications) return DevelopmentNotifications(context).openSettings()
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val intents = buildList {
            when (permission) {
                AppPermission.Notifications -> Unit
                AppPermission.Files -> if (Build.VERSION.SDK_INT >= 30) {
                    add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
                    add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                AppPermission.InstallPackages -> add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
                AppPermission.Overlay -> add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
                AppPermission.Accessibility -> add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                AppPermission.Battery -> add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        for (intent in intents) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        return false
    }
}
