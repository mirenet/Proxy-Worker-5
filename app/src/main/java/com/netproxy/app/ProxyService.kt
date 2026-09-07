package com.netproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ProxyService : Service() {

    companion object {
        var proxyServer: LocalProxyServer? = null
        var isServerRunning: Boolean = false
        var activePort: Int = 48912
        const val ACTION_STOP = "com.netproxy.app.STOP_SERVER"
    }

    private val CHANNEL_ID = "NetProxyChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra("PORT", 48912) ?: 48912
        val password = intent?.getStringExtra("PASSWORD") ?: "M2K938si7MwAb29shoLHew2B9hwx7N2oZwbd18"

        activePort = port

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
       
        val largeIconBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
       
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Worker Running..")
            .setContentText("Server is active on port $port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setLargeIcon(largeIconBitmap)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        if (proxyServer == null) {
            try {
                proxyServer = LocalProxyServer(port, password, applicationContext)
                proxyServer?.start()
                isServerRunning = true
            } catch (e: Exception) {
                e.printStackTrace()
                isServerRunning = false
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            proxyServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        proxyServer = null
        isServerRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "NetProxy Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
