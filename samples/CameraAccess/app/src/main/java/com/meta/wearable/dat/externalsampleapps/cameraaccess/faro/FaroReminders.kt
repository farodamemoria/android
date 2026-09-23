package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

object FaroNotify {
  private const val BASE = "https://d2n7ih9kfxbzvd.cloudfront.net"
  private const val TOKEN = "local-development-only"

  fun post(path: String) {
    Thread {
          try {
            val connection = URL(BASE + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $TOKEN")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.write("{}".toByteArray())
            connection.responseCode
          } catch (error: Exception) {
            Log.w("FaroNotify", "post failed: ${error.message}")
          }
        }
        .start()
  }

  fun notWorn() = post("/v1/glasses/not-worn")
}

class FaroReminderService : Service() {
  private var running = true

  override fun onCreate() {
    super.onCreate()
    getSystemService(NotificationManager::class.java)
        ?.createNotificationChannel(
            NotificationChannel("faro_reminders", "Recordatorios Faro", NotificationManager.IMPORTANCE_LOW)
        )
    startForeground(
        4271,
        Notification.Builder(this, "faro_reminders")
            .setContentTitle("Faro")
            .setContentText("Escuchando recordatorios")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build(),
    )
    Thread {
          while (running) {
            try {
              val connection = URL("$BASE/v1/voice-reminders").openConnection() as HttpURLConnection
              connection.setRequestProperty("Authorization", "Bearer $TOKEN")
              if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val reminders = JSONArray(body)
                for (index in 0 until reminders.length()) {
                  val message = reminders.getJSONObject(index).optString("message")
                  if (message.isNotEmpty()) FaroSpeaker.speak(applicationContext, message)
                }
              }
            } catch (error: Exception) {
              Log.w("FaroReminders", "poll failed: ${error.message}")
            }
            Thread.sleep(5000L)
          }
        }
        .start()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

  override fun onDestroy() {
    running = false
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val BASE = "https://d2n7ih9kfxbzvd.cloudfront.net"
    const val TOKEN = "local-development-only"

    fun start(context: Context) {
      runCatching {
        androidx.core.content.ContextCompat.startForegroundService(
            context, Intent(context, FaroReminderService::class.java)
        )
      }
    }
  }
}

class FaroBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val service = Intent(context, FaroReminderService::class.java)
    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service)
    else context.startService(service)
  }
}
