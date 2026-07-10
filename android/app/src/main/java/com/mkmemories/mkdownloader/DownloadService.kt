package com.mkmemories.mkdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service de premier plan qui maintient les téléchargements actifs même quand
 * l'application est fermée, et affiche une notification de progression.
 * La logique de file vit dans [Downloads] ; ce service la garde en vie et
 * reflète son état.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        Downloads.notifier = { refresh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!Downloads.hasActive()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Downloads.notifier = null
        super.onDestroy()
    }

    /** Rafraîchit ou retire la notification selon l'état de la file. */
    private fun refresh() {
        if (!Downloads.hasActive()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        NotificationManagerCompat.from(this).let { nm ->
            if (hasNotifPermission()) nm.notify(NID, buildNotification())
        }
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val active = Downloads.active()
        val done = Downloads.doneCount()
        val total = Downloads.totalCount()
        val remaining = total - done
        val title = getString(R.string.dl_notif_title, done + (if (active?.status == Downloads.Status.RUNNING) 1 else 0), total)
        val text = active?.item?.title ?: getString(R.string.dl_notif_working)
        val percent = active?.percent ?: -1

        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (remaining > 1) setSubText(getString(R.string.dl_notif_remaining, remaining)) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, getString(R.string.dl_notif_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.dl_notif_channel) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun hasNotifPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    companion object {
        private const val CHANNEL = "downloads"
        private const val NID = 4211

        fun start(context: Context) {
            if (!Downloads.hasActive()) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
