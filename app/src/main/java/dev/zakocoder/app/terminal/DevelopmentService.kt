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
        (application as ZakoApplication).workbench.serviceDestroyed(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
