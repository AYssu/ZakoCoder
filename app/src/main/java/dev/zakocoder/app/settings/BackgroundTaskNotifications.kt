package dev.zakocoder.app.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R

/** Generic task notifications. No source, prompt, path, server address or model reply is displayed. */
internal class BackgroundTaskNotifications(private val context: Context) {
    companion object {
        const val CHANNEL = "assistant-results"
        const val ACTION_OPEN_TASK = "dev.zakocoder.app.OPEN_ASSISTANT_TASK"
        const val EXTRA_PROJECT_ID = "background_task_project"
        const val EXTRA_CONVERSATION_ID = "background_task_conversation"
        private const val RESULT_ID = 9

        fun openIntent(context: Context, projectId: String?, conversationId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (projectId != null && conversationId != null) {
                // data participates in PendingIntent identity; separate conversations cannot overwrite each other.
                intent.data = Uri.Builder().scheme("zakocoder").authority("assistant-task")
                    .appendPath(projectId).appendPath(conversationId).build()
                intent.putExtra(EXTRA_PROJECT_ID, projectId).putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    fun show(task: AiBackgroundTask, notice: BackgroundTaskNotice) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.background_results_channel), NotificationManager.IMPORTANCE_HIGH))
        val (title, body) = when (notice) {
            BackgroundTaskNotice.Completed -> R.string.background_completed_title to R.string.background_completed_body
            BackgroundTaskNotice.Failed -> R.string.background_failed_title to R.string.background_failed_body
            BackgroundTaskNotice.Approval -> R.string.background_approval_title to R.string.background_approval_body
            BackgroundTaskNotice.Answer -> R.string.background_answer_title to R.string.background_answer_body
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(title)).setContentText(context.getString(body))
            .setContentIntent(openIntent(context, task.projectId, task.conversationId))
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if (notice in setOf(BackgroundTaskNotice.Approval, BackgroundTaskNotice.Answer))
                NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(false).build()
        try { manager.notify(task.conversationId, RESULT_ID, notification) }
        catch (_: SecurityException) { /* Notification access may change after checking; task execution is independent. */ }
    }

    fun clear(conversationId: String) = context.getSystemService(NotificationManager::class.java)
        .cancel(conversationId, RESULT_ID)
}
