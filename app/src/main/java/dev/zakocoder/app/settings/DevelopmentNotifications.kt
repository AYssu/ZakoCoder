package dev.zakocoder.app.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class RunningNotificationStatus { PermissionRequired, AppBlocked, ChannelBlocked, Available, Unknown }

internal fun runningNotificationStatus(permission: Boolean, appEnabled: Boolean, channelEnabled: Boolean, groupEnabled: Boolean): RunningNotificationStatus =
    when {
        !permission -> RunningNotificationStatus.PermissionRequired
        !appEnabled -> RunningNotificationStatus.AppBlocked
        !channelEnabled || !groupEnabled -> RunningNotificationStatus.ChannelBlocked
        else -> RunningNotificationStatus.Available
    }

/** Read-only capability check. Does not create channels, post notifications or change permission. */
class DevelopmentNotifications(private val context: Context, private val channelId: String = CHANNEL) {
    companion object { const val CHANNEL = "development" }
    fun status(): RunningNotificationStatus = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(channelId)
        val groupEnabled = if (Build.VERSION.SDK_INT >= 28) channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked != true } ?: true else true
        runningNotificationStatus(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            NotificationManagerCompat.from(context).areNotificationsEnabled(), channel?.importance != NotificationManager.IMPORTANCE_NONE, groupEnabled)
    } catch (_: RuntimeException) { RunningNotificationStatus.Unknown }

    /** Explicit settings navigation only; an unavailable vendor screen falls back to app details. */
    fun openSettings(): Boolean {
        val intents = buildList {
            val exists = try { context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId) != null } catch (_: RuntimeException) { false }
            if (exists) add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId))
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        for (intent in intents) try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
        catch (_: android.content.ActivityNotFoundException) { }
        catch (_: SecurityException) { }
        return false
    }
}
