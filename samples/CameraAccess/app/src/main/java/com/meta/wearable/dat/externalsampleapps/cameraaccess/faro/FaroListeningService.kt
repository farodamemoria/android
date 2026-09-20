package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

/** Servicio en primer plano (tipo microphone) que mantiene la escucha de las gafas en segundo plano. */
class FaroListeningService : Service() {
  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
          NotificationChannel(CHANNEL, "Faro · seguridad", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val notification: Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Faro")
            .setContentText("Escuchando para tu seguridad")
            .setSmallIcon(R.drawable.camera_access_icon)
            .setOngoing(true)
            .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    FaroAudioMonitor.start(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onDestroy() {
    FaroAudioMonitor.stop()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL = "faro_listening"
    private const val NOTIFICATION_ID = 4272

    fun start(context: Context) {
      runCatching {
        ContextCompat.startForegroundService(context, Intent(context, FaroListeningService::class.java))
      }
    }
  }
}
