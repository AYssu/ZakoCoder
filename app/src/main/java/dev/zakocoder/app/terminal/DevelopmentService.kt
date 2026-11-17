package dev.zakocoder.app.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.zakocoder.app.R
import dev.zakocoder.app.ZakoApplication
import dev.zakocoder.app.settings.AiBackgroundTask
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.settings.BackgroundTaskNotifications

class DevelopmentService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var taskActive = false
    private var assistant: AiBackgroundTask? = null
    private var promoted = false
    private var refreshPending = false
    private val refreshNotification = Runnable { refreshPending = false; if (promoted) promote() }

    /** Throttle phase/counter changes; streaming text never enters the foreground notification. */
    internal fun updateAssistant(task: AiBackgroundTask) {
        if (assistant == task) return
        assistant = task
        if (!refreshPending) { refreshPending = true; handler.postDelayed(refreshNotification, 750) }
    }
    private val executionLock by lazy {
        getSystemService(android.os.PowerManager::class.java).newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:development-task"
        ).apply { setReferenceCounted(false) }
    }
    private val renewLock = object : Runnable {
        override fun run() {
            if (!taskActive) return
            if (executionLock.isHeld) executionLock.release()
            executionLock.acquire(10 * 60 * 1000L)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }
    /** Idle terminals and an idle floating control do not keep the CPU awake. */
    fun setTaskActive(active: Boolean) {
        if (taskActive == active) return
        taskActive = active
        handler.removeCallbacks(renewLock)
        if (active) renewLock.run() else if (executionLock.isHeld) executionLock.release()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(dev.zakocoder.app.settings.DevelopmentNotifications.CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
    }
    private fun promote() {
        val task = assistant?.takeIf { it.active }
        val open = BackgroundTaskNotifications.openIntent(this, task?.projectId, task?.conversationId)
        val stop = PendingIntent.getService(this, 1, Intent(this, DevelopmentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, dev.zakocoder.app.settings.DevelopmentNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(task?.phase?.label ?: R.string.service_running))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.stop_all), stop)
        if (task != null) {
            notification.setSubText(getString(R.string.background_progress, task.round.coerceAtLeast(1), task.tools))
            if (task.needsCpu) {
                notification.setProgress(0, 0, true)
                    .setRequestPromotedOngoing(true)
                    .setShortCriticalText(getString(when (task.phase) {
                        AiPhase.Requesting -> R.string.background_chip_thinking
                        AiPhase.Receiving -> R.string.background_chip_receiving
                        AiPhase.Executing -> R.string.background_chip_executing
                        AiPhase.Compacting -> R.string.background_chip_compacting
                        else -> R.string.background_chip_running
                    }))
            }
        }
        startForeground(NOTIFICATION_ID, notification.build())
        promoted = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        (application as ZakoApplication).workbench.serviceReady(this)
        if (intent?.action == ACTION_STOP) (application as ZakoApplication).workbench.stopAll()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        promoted = false
        handler.removeCallbacks(refreshNotification)
        refreshPending = false
        setTaskActive(false)
        (application as ZakoApplication).workbench.serviceDestroyed(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val ACTION_STOP = "STOP"; const val NOTIFICATION_ID = 7 }
}
