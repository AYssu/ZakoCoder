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

internal data class NotificationChannelAvailability(val id: String, val channelEnabled: Boolean, val groupEnabled: Boolean)

internal fun runningNotificationStatus(permission: Boolean, appEnabled: Boolean,
    channels: List<NotificationChannelAvailability>): RunningNotificationStatus =
    runningNotificationStatus(permission, appEnabled, channels.all { it.channelEnabled }, channels.all { it.groupEnabled })

/** An explicit channel keeps single-channel semantics; aggregate settings point to a blocked channel. */
internal fun notificationSettingsChannel(channelId: String?, channels: List<NotificationChannelAvailability>): String? =
    if (channelId != null) channels.firstOrNull { it.id == channelId }?.id
    else channels.firstOrNull { !it.channelEnabled || !it.groupEnabled }?.id

/** Read-only capability check. Missing channels are not created or treated as user-disabled. */
class DevelopmentNotifications(private val context: Context, private val channelId: String? = null) {
    companion object { const val CHANNEL = "development" }
    private fun channels(): List<NotificationChannelAvailability> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ids = channelId?.let(::listOf) ?: listOf(CHANNEL, BackgroundTaskNotifications.CHANNEL)
        return ids.mapNotNull { id ->
            val channel = manager.getNotificationChannel(id) ?: return@mapNotNull null
            val groupEnabled = if (Build.VERSION.SDK_INT >= 28)
                channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked != true } ?: true else true
            NotificationChannelAvailability(id, channel.importance != NotificationManager.IMPORTANCE_NONE, groupEnabled)
        }
    }

    fun status(): RunningNotificationStatus = try {
        runningNotificationStatus(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            NotificationManagerCompat.from(context).areNotificationsEnabled(), channels())
    } catch (_: RuntimeException) { RunningNotificationStatus.Unknown }

    /** Explicit settings navigation only; an unavailable vendor screen falls back to app details. */
    fun openSettings(): Boolean {
        val intents = buildList {
            val targetChannel = try { notificationSettingsChannel(channelId, channels()) } catch (_: RuntimeException) { null }
            if (targetChannel != null) add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, targetChannel))
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        for (intent in intents) try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
        catch (_: android.content.ActivityNotFoundException) { }
        catch (_: SecurityException) { }
        return false
    }
}
