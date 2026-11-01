package dev.zakocoder.app.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ZakoApplication

class DevelopmentService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var taskActive = false
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DevelopmentService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7, NotificationCompat.Builder(this, dev.zakocoder.app.settings.DevelopmentNotifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running)).setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.stop_all), stop).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        (application as ZakoApplication).workbench.serviceReady(this)
        if (intent?.action == "STOP") (application as ZakoApplication).workbench.stopAll()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        setTaskActive(false)
        (application as ZakoApplication).workbench.serviceDestroyed(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
