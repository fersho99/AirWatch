package com.airwatch.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano: mantiene vivo el proceso (sensores + red) con la pantalla apagada
 * o la app en segundo plano. Tipo "connectedDevice" porque mantiene un enlace con el PC.
 */
class AirMouseService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airwatch:service")
                .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        }
        Engine.startSession(
            pair = intent?.getBooleanExtra(EXTRA_PAIR, false) == true,
            host = intent?.getStringExtra(EXTRA_HOST),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Engine.stopSession()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AirMouseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.airwatch.wear.STOP"
        const val EXTRA_PAIR = "pair"
        const val EXTRA_HOST = "host"
        private const val CHANNEL = "airwatch_session"
        private const val NOTIF_ID = 1
    }
}
